package com.starcon.trade;

import static com.starcon.trade.DropshipTrackingModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.6.1.3 代发履约跟踪与异常。
 *
 * <p><b>七条实现纪律</b>（照抄 9.6.1.1 / 9.6.1.2 的写法，不另起风格）：
 *
 * <ol>
 *   <li><b>两段物流不合成。</b>{@code supplierLegStatus} 与 {@code ownLegStatus} 是两组
 *       各自独立的列，各带各的承运商与运单号。「改自营发货」时供应商段落
 *       ABANDONED（<b>保留</b>为凭据），我方段才开始推进 —— 合成一列就会把
 *       「供应商发过货」这个事实覆盖掉，而跑单纠纷问的恰好是这个。</li>
 *   <li><b>异常由规则检出，不手工标记。</b>三条判据（超时未发货 / 轨迹长时间无更新 /
 *       供应商报发货后无运单）是 {@link #evaluateTrackingExceptions()} 里三条
 *       <b>带 now() 的 SQL</b>，阈值取自租户策略视图；每条异常把当时用的阈值与算式
 *       写进自己的行（rule_threshold_hours / rule_evidence）。</li>
 *   <li><b>处置是真动作，不是勾。</b>催单 / 改自营 / 退款各自落 disposition（带状态机）
 *       + task（跨服务待办）+ 事件流。改自营还会真的把 active_leg 翻过去、
 *       把未决异常置为 DISPOSED —— 这就是「异常必须可发现<b>可切换</b>」。</li>
 *   <li><b>下游未交付时落任务，不落假结果。</b>回传平台（2.5.1.1 / channel）与
 *       退款（11 售后）写进 {@code fulfillment_dropship_task}，status 从 OPEN 起；
 *       只有下游真的接手才推进到 SENT / ACKNOWLEDGED。</li>
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 写在 values 的对应位置上，
 *       cast 不出现在绑定序列末位；全程 {@link OffsetDateTime}。</li>
 *   <li><b>金额用 BigDecimal</b>，退款金额按 numeric(19,4) 落库，不走浮点。</li>
 *   <li><b>不用 JPA 懒加载</b>：全部 jOOQ 显式 SQL。</li>
 * </ol>
 *
 * <p><b>跨服务一律不读表</b>：2.5.1.1（channel）、11 售后、10 物流各在自己的库里，
 * 本模块一条 SQL 都不碰；上游 9.6.1.2 代发单<b>同属 trade 库</b>，因此可以 FK 引用
 * （模块边界纪律禁的是跨服务 FK，不是同库引用）。
 */
@Service
public class DropshipTrackingService {

  /** 单页最大行数（与 9.6.1.1 / 9.6.1.2 同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  private static final int MONEY_SCALE = 4;

  /** 代发单进入这三个状态之后才谈得上「跟踪供应商发货」。 */
  private static final List<String> TRACKABLE_ORDER_STATUSES =
      List.of("DISPATCHED", "ACCEPTED", "SHIPPED");

  /** 供应商段已发货后的三个状态：有运单号就该回传平台。 */
  private static final List<String> SHIPPED_SUPPLIER_STATUSES =
      List.of("SHIPPED", "IN_TRANSIT", "DELIVERED");

  private static final String TRACKING_COLUMNS =
      "id,tracking_no,order_id,order_no,sales_order_id,sales_order_no,supplier_code,active_leg,"
          + "supplier_leg_status,supplier_carrier_code,supplier_tracking_no,supplier_shipped_at,"
          + "supplier_last_event_at,supplier_delivered_at,supplier_promised_ship_at,"
          + "supplier_exception_note,own_leg_status,own_carrier_code,own_tracking_no,"
          + "own_handed_over_at,own_last_event_at,own_delivered_at,own_delivery_note,"
          + "switched_to_own_at,switched_to_own_by_membership_id,switch_reason,urge_count,"
          + "last_urged_at,callback_status,callback_sent_at,callback_attempts,callback_last_error,"
          + "note,version,created_at,updated_at";

  private static final String EXCEPTION_COLUMNS =
      "id,exception_no,tracking_id,order_id,order_no,sales_order_no,supplier_code,exception_kind,"
          + "severity,rule_code,rule_threshold_hours,rule_evidence,status,acknowledged_at,"
          + "acknowledged_by_membership_id,resolution_note,resolved_at,resolved_by_membership_id,"
          + "detected_at";

  private static final String DISPOSITION_COLUMNS =
      "id,disposition_no,exception_id,tracking_id,order_id,order_no,action_kind,status,"
          + "refund_amount,refund_currency_code,target_system,note,created_by_membership_id,"
          + "sent_at,acknowledged_at,completed_at,cancelled_at,outcome,created_at";

  private static final String TASK_COLUMNS =
      "id,task_no,task_kind,task_status,tracking_id,order_id,order_no,sales_order_no,"
          + "disposition_id,exception_id,target_system,payload,idempotency_key,attempt_count,"
          + "last_attempt_at,last_error,claimed_by_membership_id,claimed_at,finished_at,created_at";

  private static final String EVENT_COLUMNS =
      "id,tracking_id,event_kind,from_active_leg,to_active_leg,from_supplier_leg_status,"
          + "to_supplier_leg_status,from_own_leg_status,to_own_leg_status,detail,"
          + "actor_membership_id,occurred_at";

  private final DSLContext sql;
  private final ObjectMapper json;

  public DropshipTrackingService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>两处 available=false 且点名 ownerModule：<b>平台回传</b>（2.5.1.1 / channel）
   * 与 <b>售后退款</b>（11）。它们在本模块里的真实形态是**任务**（task_status=OPEN），
   * 不是已完成的动作 —— 报成 true 会让人以为回传与退款已经通了。
   */
  public DropshipTrackingCapabilities capabilities() {
    List<DropshipTrackingCapabilityRule> rules =
        Arrays.stream(DropshipExceptionKind.values())
            .map(
                k ->
                    new DropshipTrackingCapabilityRule(
                        k.name(),
                        k.label(),
                        k.ruleCode(),
                        k.ruleSummary(),
                        k.suggestedActions(),
                        k.defaultSeverity()))
            .toList();
    return new DropshipTrackingCapabilities(
        Arrays.stream(DropshipSupplierLegStatus.values()).map(Enum::name).toList(),
        Arrays.stream(DropshipOwnLegStatus.values()).map(Enum::name).toList(),
        Arrays.stream(DropshipExceptionKind.values()).map(Enum::name).toList(),
        Arrays.stream(DropshipDispositionAction.values()).map(Enum::name).toList(),
        Arrays.stream(DropshipTaskKind.values()).map(Enum::name).toList(),
        rules,
        List.of(
            new DropshipTrackingCapabilityDimension(
                "DROPSHIP_ORDER",
                "9.6.1.2 代发单（供应商 / 单号 / 发货状态）",
                true,
                "9.6.1.2 V122（fulfillment_dropship_order）→ 本模块同库 FK 引用",
                null,
                null),
            new DropshipTrackingCapabilityDimension(
                "SUPPLIER_LEG",
                "供应商发货与物流状态（接口或录入）",
                true,
                "蓝图输入句「供应商发货/物流状态（接口或录入）」——人工录入路径已接线",
                null,
                "轨迹最后更新时刻 supplier_last_event_at 必须由观测方给；用写入时刻冒充"
                    + "会让停滞检测永远算不出静默时长，丢件就永远检不出来"),
            new DropshipTrackingCapabilityDimension(
                "PLATFORM_CALLBACK",
                "同步供应商运单号回传平台",
                false,
                "2.5.1.1 平台回传（channel 服务）未交付",
                "2.5.1.1",
                "本模块在 trade 侧落 fulfillment_dropship_task（PLATFORM_CALLBACK →"
                    + " CHANNEL），运单号与承运商放在 payload 里，幂等键去重；"
                    + "callback_status 在下游接手前停在 PENDING/QUEUED，"
                    + "**不会**出现「已回传」这种假结论"),
            new DropshipTrackingCapabilityDimension(
                "REFUND",
                "退款处置（11 售后）",
                false,
                "11 售后未交付",
                "11 售后",
                "发起退款落 fulfillment_dropship_task（REFUND_REQUEST → AFTER_SALE），"
                    + "金额与币种落在 disposition（numeric，不用浮点）；"
                    + "本模块**不代为退款**，只登记待办"),
            new DropshipTrackingCapabilityDimension(
                "SELF_FULFILLED",
                "改自营发货（自有仓承接）",
                false,
                "9.4.1.2 发货记录（同组未交付）",
                "9.4.1.2",
                "「改自营发货」在本模块完成的是：active_leg 翻到 OWN、供应商段落"
                    + " ABANDONED 留痕、未决异常置 DISPOSED，并落 task 交给自有仓；"
                    + "真正建单发货属 9.4.1.2，本模块**不去碰**它的表"),
            new DropshipTrackingCapabilityDimension(
                "TRACK_TRACE",
                "供应商侧轨迹（10.3）",
                false,
                "10.3 轨迹未交付",
                "10.3",
                "本模块接收轨迹观测并推进供应商段状态（SHIPPED/IN_TRANSIT/DELIVERED/LOST）"
                    + "，但**不拥有**轨迹本身；轨迹点的明细归 10.3")),
        false,
        false,
        "两段物流分列是本模块唯一不可让步的一条：供应商段与我方段各带各的承运商、运单号、"
            + "状态枚举与时间戳，「改自营发货」时供应商段落 ABANDONED 而非被覆盖。",
        "代发模式下「及时发货」是供应商责任但平台考核算我方（蓝图原话），"
            + "因此延迟、丢件、供应商跑单三类异常必须**由规则检出**并可切换："
            + "超时未发货按承诺时限判（supplier_promised_ship_at，延迟检测的唯一基准），"
            + "丢件按轨迹静默时长判，跑单按「报发货却无运单号」判。"
            + "阈值在租户策略里改，且每条异常都记下当时用的阈值与算式。");
  }

  // ================================================================ 检出策略

  /** 有效阈值（未登记时返回迁移内的默认值，并如实报出 policyRegistered=false）。 */
  public DropshipTrackingPolicyRow policy() {
    var row =
        sql.fetchOne(
            "select policy_registered,delay_tolerance_hours,critical_delay_hours,"
                + "stall_threshold_hours,critical_stall_hours,require_tracking_no_on_shipped "
                + "from fulfillment_dropship_tracking_policy_effective");
    var stored =
        sql.fetchOne(
            "select id,delay_tolerance_hours,critical_delay_hours,stall_threshold_hours,"
                + "critical_stall_hours,require_tracking_no_on_shipped,note,updated_at "
                + "from fulfillment_dropship_tracking_policy");
    return new DropshipTrackingPolicyRow(
        stored == null ? null : stored.get("id", UUID.class),
        row.get("policy_registered", Boolean.class),
        row.get("delay_tolerance_hours", Integer.class),
        row.get("critical_delay_hours", Integer.class),
        row.get("stall_threshold_hours", Integer.class),
        row.get("critical_stall_hours", Integer.class),
        row.get("require_tracking_no_on_shipped", Boolean.class),
        stored == null ? "尚未登记：当前使用迁移内置默认值" : stored.get("note", String.class),
        stored == null ? null : stored.get("updated_at", OffsetDateTime.class));
  }

  /** 维护检出策略（租户级单例，upsert）。 */
  @Transactional
  public DropshipTrackingPolicyRow savePolicy(DropshipTrackingPolicyInput input, UUID membershipId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into fulfillment_dropship_tracking_policy (id,tenant_id,singleton,"
            + "delay_tolerance_hours,critical_delay_hours,stall_threshold_hours,"
            + "critical_stall_hours,require_tracking_no_on_shipped,note,updated_by_membership_id,"
            + "created_at,updated_at) "
            + "values (?,?,true,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz) "
            + "on conflict (tenant_id,singleton) do update set "
            + "delay_tolerance_hours = excluded.delay_tolerance_hours,"
            + "critical_delay_hours = excluded.critical_delay_hours,"
            + "stall_threshold_hours = excluded.stall_threshold_hours,"
            + "critical_stall_hours = excluded.critical_stall_hours,"
            + "require_tracking_no_on_shipped = excluded.require_tracking_no_on_shipped,"
            + "note = excluded.note, updated_by_membership_id = excluded.updated_by_membership_id,"
            + "updated_at = excluded.updated_at",
        UUID.randomUUID(),
        currentTenant(),
        intOr(input.delayToleranceHours(), 0),
        intOr(input.criticalDelayHours(), 24),
        intOr(input.stallThresholdHours(), 72),
        intOr(input.criticalStallHours(), 120),
        input.requireTrackingNoOnShipped() == null || input.requireTrackingNoOnShipped(),
        nz(input.note()).trim(),
        membershipId,
        now,
        now);
    return policy();
  }

  // ================================================================ 跟踪

  /** 跟踪列表（真分页；{@code exceptionOnly=true} 只回有未结案异常的那些）。 */
  public DropshipTrackingPage pageTrackings(DropshipTrackingQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.orderNo())) {
      where.add("order_no = ?");
      binds.add(query.orderNo().trim());
    }
    if (notBlank(query.salesOrderNo())) {
      where.add("sales_order_no = ?");
      binds.add(query.salesOrderNo().trim());
    }
    if (notBlank(query.supplierCode())) {
      where.add("supplier_code = ?");
      binds.add(query.supplierCode().trim());
    }
    if (notBlank(query.activeLeg())) {
      where.add("active_leg = ?");
      binds.add(activeLeg(query.activeLeg()).name());
    }
    if (notBlank(query.supplierLegStatus())) {
      where.add("supplier_leg_status = ?");
      binds.add(supplierLegStatus(query.supplierLegStatus()).name());
    }
    if (notBlank(query.ownLegStatus())) {
      where.add("own_leg_status = ?");
      binds.add(ownLegStatus(query.ownLegStatus()).name());
    }
    if ("true".equalsIgnoreCase(nz(query.exceptionOnly()))) {
      where.add(
          "exists (select 1 from fulfillment_dropship_tracking_exception e "
              + "where e.tracking_id = fulfillment_dropship_tracking.id and e.status <> 'DISMISSED')");
    }
    if (notBlank(query.keyword())) {
      where.add(
          "(order_no ilike ? or sales_order_no ilike ? or tracking_no ilike ?"
              + " or supplier_tracking_no ilike ? or own_tracking_no ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_tracking" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<DropshipTrackingRow> items =
        sql.fetch(
                "select " + TRACKING_COLUMNS + " from fulfillment_dropship_tracking" + sqlWhere
                    + " order by updated_at desc limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipTrackingService::trackingRow);
    return new DropshipTrackingPage(items, total, pageNo, size);
  }

  /** 跟踪详情：跟踪头 + 异常 + 处置 + 任务 + 事件流。跨租户在 RLS 下查不到 → 404。 */
  public DropshipTrackingDetail trackingDetail(UUID trackingId) {
    Record tracking = requireTracking(trackingId);
    List<DropshipTrackingExceptionRow> exceptions =
        sql.fetch(
                "select " + EXCEPTION_COLUMNS
                    + " from fulfillment_dropship_tracking_exception where tracking_id = ?"
                    + " order by detected_at desc",
                trackingId)
            .map(DropshipTrackingService::exceptionRow);
    List<DropshipDispositionRow> dispositions =
        sql.fetch(
                "select " + DISPOSITION_COLUMNS
                    + " from fulfillment_dropship_disposition where tracking_id = ?"
                    + " order by created_at desc",
                trackingId)
            .map(DropshipTrackingService::dispositionRow);
    List<DropshipTaskRow> tasks =
        sql.fetch(
                "select " + TASK_COLUMNS
                    + " from fulfillment_dropship_task where tracking_id = ?"
                    + " order by created_at desc",
                trackingId)
            .map(this::taskRow);
    List<DropshipTrackingEventRow> events =
        sql.fetch(
                "select " + EVENT_COLUMNS
                    + " from fulfillment_dropship_tracking_event where tracking_id = ?"
                    + " order by occurred_at desc limit 200",
                trackingId)
            .map(DropshipTrackingService::eventRow);
    return new DropshipTrackingDetail(
        trackingRow(tracking), exceptions, dispositions, tasks, events);
  }

  /**
   * 建跟踪：一张代发单一条跟踪。
   *
   * <p>承诺发货时限必填 —— 没有承诺时限就无从「迟」，而这恰恰是平台及时发货考核的依据；
   * 把它留空等于让延迟检测对这张单**永久失效**，而不是「暂不检测」。
   */
  @Transactional
  public DropshipTrackingSaved createTracking(DropshipTrackingInput input, UUID membershipId) {
    if (input == null || input.orderId() == null || input.supplierPromisedShipAt() == null) {
      throw bad("代发单与承诺发货时限都不能为空：承诺时限是延迟判定的唯一基准");
    }
    var existing =
        sql.fetchOne(
            "select id,tracking_no from fulfillment_dropship_tracking where order_id = ?",
            input.orderId());
    if (existing != null) {
      return new DropshipTrackingSaved(
          existing.get("id", UUID.class), existing.get("tracking_no", String.class), false);
    }
    var order =
        sql.fetchOne(
            "select order_no,sales_order_id,sales_order_no,supplier_code,status "
                + "from fulfillment_dropship_order where id = ?",
            input.orderId());
    if (order == null) {
      throw notFound("代发单不存在或不属于当前租户");
    }
    String orderStatus = order.get("status", String.class);
    if (!TRACKABLE_ORDER_STATUSES.contains(orderStatus)) {
      throw bad(
          "代发单当前是 "
              + orderStatus
              + "，还没提交给供应商（需 DISPATCHED / ACCEPTED / SHIPPED）——"
              + "供应商尚未接到这票货时，「跟踪供应商发货」这件事还不存在");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into fulfillment_dropship_tracking (id,tenant_id,tracking_no,order_id,order_no,"
            + "sales_order_id,sales_order_no,supplier_code,active_leg,supplier_leg_status,"
            + "supplier_promised_ship_at,own_leg_status,note,created_by_membership_id,"
            + "created_at,updated_at) "
            + "values (?,?," + no("DTR", "dropship_tracking_no_seq") + ",?,?,?,?,?,'SUPPLIER',"
            + "'NOT_SHIPMENT',?::timestamptz,'NOT_HANDED_OVER',?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        input.orderId(),
        order.get("order_no", String.class),
        order.get("sales_order_id", UUID.class),
        order.get("sales_order_no", String.class),
        order.get("supplier_code", String.class),
        input.supplierPromisedShipAt(),
        nz(input.note()).trim(),
        membershipId,
        now,
        now);
    appendEvent(
        id,
        "TRACKING_CREATED",
        "SUPPLIER",
        "SUPPLIER",
        "NOT_SHIPMENT",
        "NOT_SHIPMENT",
        "NOT_HANDED_OVER",
        "NOT_HANDED_OVER",
        "建立代发履约跟踪，承诺发货时限 " + input.supplierPromisedShipAt(),
        membershipId);
    return new DropshipTrackingSaved(id, trackingNo(id), true);
  }

  /**
   * 录入/更新供应商段。
   *
   * <p>「供应商报发货且给了运单号」时自动落一条 <b>PLATFORM_CALLBACK 任务</b>：
   * 运单号与承运商进 payload（不回传整张单），幂等键去重 —— 重复录同一张发货回执
   * 不会造出第二条要下游执行的回传。
   */
  @Transactional
  public DropshipTrackingSaved recordSupplierLeg(
      UUID trackingId, DropshipSupplierLegInput input, UUID membershipId) {
    if (input == null || !notBlank(input.supplierLegStatus())) {
      throw bad("请给出供应商段状态");
    }
    Record before = requireTracking(trackingId);
    DropshipSupplierLegStatus target = supplierLegStatus(input.supplierLegStatus());
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update fulfillment_dropship_tracking set supplier_leg_status = ?,"
            + "supplier_carrier_code = ?, supplier_tracking_no = ?,"
            + "supplier_shipped_at = coalesce(?::timestamptz, supplier_shipped_at),"
            + "supplier_last_event_at = coalesce(?::timestamptz, supplier_last_event_at),"
            + "supplier_delivered_at = coalesce(?::timestamptz, supplier_delivered_at),"
            + "supplier_exception_note = ?, version = version + 1, updated_at = ?::timestamptz "
            + "where id = ?",
        target.name(),
        nz(input.supplierCarrierCode()).trim(),
        nz(input.supplierTrackingNo()).trim(),
        input.supplierShippedAt(),
        input.supplierLastEventAt(),
        input.supplierDeliveredAt(),
        nz(input.supplierExceptionNote()).trim(),
        now,
        trackingId);
    appendEvent(
        trackingId,
        "SUPPLIER_LEG_UPDATED",
        before.get("active_leg", String.class),
        before.get("active_leg", String.class),
        before.get("supplier_leg_status", String.class),
        target.name(),
        before.get("own_leg_status", String.class),
        before.get("own_leg_status", String.class),
        "供应商段 " + before.get("supplier_leg_status", String.class) + " → " + target.name(),
        membershipId);
    Record after = requireTracking(trackingId);
    maybeEnsureCallbackTask(after, membershipId);
    return new DropshipTrackingSaved(trackingId, after.get("tracking_no", String.class), false);
  }

  /** 录入/更新我方段（切换履约模式后这一段才开始推进）。 */
  @Transactional
  public DropshipTrackingSaved recordOwnLeg(
      UUID trackingId, DropshipOwnLegInput input, UUID membershipId) {
    if (input == null || !notBlank(input.ownLegStatus())) {
      throw bad("请给我方段状态");
    }
    Record before = requireTracking(trackingId);
    DropshipOwnLegStatus target = ownLegStatus(input.ownLegStatus());
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update fulfillment_dropship_tracking set own_leg_status = ?,"
            + "own_carrier_code = ?, own_tracking_no = ?,"
            + "own_handed_over_at = coalesce(?::timestamptz, own_handed_over_at),"
            + "own_last_event_at = coalesce(?::timestamptz, own_last_event_at),"
            + "own_delivered_at = coalesce(?::timestamptz, own_delivered_at),"
            + "own_delivery_note = ?, version = version + 1, updated_at = ?::timestamptz "
            + "where id = ?",
        target.name(),
        nz(input.ownCarrierCode()).trim(),
        nz(input.ownTrackingNo()).trim(),
        input.ownHandedOverAt(),
        input.ownLastEventAt(),
        input.ownDeliveredAt(),
        nz(input.ownDeliveryNote()).trim(),
        now,
        trackingId);
    appendEvent(
        trackingId,
        "OWN_LEG_UPDATED",
        before.get("active_leg", String.class),
        before.get("active_leg", String.class),
        before.get("supplier_leg_status", String.class),
        before.get("supplier_leg_status", String.class),
        before.get("own_leg_status", String.class),
        target.name(),
        "我方段 " + before.get("own_leg_status", String.class) + " → " + target.name(),
        membershipId);
    return new DropshipTrackingSaved(trackingId, before.get("tracking_no", String.class), false);
  }

  // ================================================================ 异常检出

  /**
   * 跑一轮检出：三条判据，三条**带 {@code now()} 的 SQL**。
   *
   * <p>判据（阈值来自 {@code fulfillment_dropship_tracking_policy_effective}，
   * 未登记时用迁移内置默认值）：
   *
   * <ol>
   *   <li><b>DELAYED_SHIPMENT</b>：生效段=供应商段、仍「未发货」、承诺时限已过宽限。
   *       逾期超 {@code critical_delay_hours} → CRITICAL。</li>
   *   <li><b>PARCEL_LOST</b>：已发货/在途、**有运单号**、轨迹
   *       {@code coalesce(supplier_last_event_at, supplier_shipped_at)}
   *       静默超 {@code stall_threshold_hours}。静默超 {@code critical_stall_hours}
   *       → CRITICAL。</li>
   *   <li><b>SUPPLIER_RUNNING_AWAY</b>：已发货/在途/妥投却**没有运单号**
   *       （且策略要求运单号）→ 恒 CRITICAL。没有单号就无法回传平台、无法举证、
   *       无法对账。</li>
   * </ol>
   *
   * <p>去重：{@code not exists} 排除该 (tracking, kind) 上未结案的异常，
   * 配合 V126 的部分唯一索引，反复跑不会刷屏；误报要显式置 DISMISSED 才解禁。
   */
  @Transactional
  public DropshipTrackingEvaluateResult evaluateTrackingExceptions() {
    long scanned =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_tracking t "
                    + "cross join fulfillment_dropship_tracking_policy_effective pol "
                    + "where t.active_leg = 'SUPPLIER'")
            .get("c", Long.class);

    int delayed =
        sql.execute(
            "insert into fulfillment_dropship_tracking_exception (id,tenant_id,exception_no,"
                + "tracking_id,order_id,order_no,sales_order_no,supplier_code,exception_kind,"
                + "severity,rule_code,rule_threshold_hours,rule_evidence,status,detected_at) "
                + "select gen_random_uuid(),t.tenant_id,"
                + no("DEX", "dropship_tracking_exception_no_seq")
                + ",t.id,t.order_id,t.order_no,t.sales_order_no,t.supplier_code,"
                + "'DELAYED_SHIPMENT',"
                + "case when round(extract(epoch from (now() - t.supplier_promised_ship_at))/3600.0,1)"
                + " > pol.critical_delay_hours then 'CRITICAL' else 'WARNING' end,"
                + "'PAST_PROMISED_SHIP_NO_SHIPMENT',pol.delay_tolerance_hours,"
                + "'承诺发货时限 ' || to_char(t.supplier_promised_ship_at,'YYYY-MM-DD HH24:MI OF') "
                + "|| ' 已过（宽限 ' || pol.delay_tolerance_hours || ' 小时），逾期 ' "
                + "|| round(extract(epoch from (now() - t.supplier_promised_ship_at))/3600.0,1)::text "
                + "|| ' 小时仍为「未发货」',"
                + "'OPEN',now() "
                + "from fulfillment_dropship_tracking t "
                + "cross join fulfillment_dropship_tracking_policy_effective pol "
                + "where t.active_leg = 'SUPPLIER' and t.supplier_leg_status = 'NOT_SHIPMENT' "
                + "and t.supplier_promised_ship_at < now() - make_interval(hours => pol.delay_tolerance_hours) "
                + "and not exists (select 1 from fulfillment_dropship_tracking_exception e "
                + "where e.tracking_id = t.id and e.exception_kind = 'DELAYED_SHIPMENT' "
                + "and e.status <> 'DISMISSED')");

    int lost =
        sql.execute(
            "insert into fulfillment_dropship_tracking_exception (id,tenant_id,exception_no,"
                + "tracking_id,order_id,order_no,sales_order_no,supplier_code,exception_kind,"
                + "severity,rule_code,rule_threshold_hours,rule_evidence,status,detected_at) "
                + "select gen_random_uuid(),t.tenant_id,"
                + no("DEX", "dropship_tracking_exception_no_seq")
                + ",t.id,t.order_id,t.order_no,t.sales_order_no,t.supplier_code,"
                + "'PARCEL_LOST',"
                + "case when round(extract(epoch from (now() - coalesce(t.supplier_last_event_at,"
                + "t.supplier_shipped_at)))/3600.0,1) > pol.critical_stall_hours "
                + "then 'CRITICAL' else 'WARNING' end,"
                + "'NO_TRACK_EVENT_BEYOND_THRESHOLD',pol.stall_threshold_hours,"
                + "'运单号 ' || t.supplier_tracking_no || ' 在途，轨迹已 ' "
                + "|| round(extract(epoch from (now() - coalesce(t.supplier_last_event_at,"
                + "t.supplier_shipped_at)))/3600.0,1)::text "
                + "|| ' 小时无更新（阈值 ' || pol.stall_threshold_hours || ' 小时）',"
                + "'OPEN',now() "
                + "from fulfillment_dropship_tracking t "
                + "cross join fulfillment_dropship_tracking_policy_effective pol "
                + "where t.active_leg = 'SUPPLIER' "
                + "and t.supplier_leg_status in ('SHIPPED','IN_TRANSIT') "
                + "and btrim(t.supplier_tracking_no) <> '' "
                + "and coalesce(t.supplier_last_event_at,t.supplier_shipped_at) "
                + "< now() - make_interval(hours => pol.stall_threshold_hours) "
                + "and not exists (select 1 from fulfillment_dropship_tracking_exception e "
                + "where e.tracking_id = t.id and e.exception_kind = 'PARCEL_LOST' "
                + "and e.status <> 'DISMISSED')");

    int runningAway =
        sql.execute(
            "insert into fulfillment_dropship_tracking_exception (id,tenant_id,exception_no,"
                + "tracking_id,order_id,order_no,sales_order_no,supplier_code,exception_kind,"
                + "severity,rule_code,rule_threshold_hours,rule_evidence,status,detected_at) "
                + "select gen_random_uuid(),t.tenant_id,"
                + no("DEX", "dropship_tracking_exception_no_seq")
                + ",t.id,t.order_id,t.order_no,t.sales_order_no,t.supplier_code,"
                + "'SUPPLIER_RUNNING_AWAY','CRITICAL','SHIPPED_WITHOUT_TRACKING_NO',0,"
                + "'供应商于 ' || coalesce(to_char(t.supplier_shipped_at,'YYYY-MM-DD HH24:MI OF'),"
                + "'未记录时刻') || ' 报「' || t.supplier_leg_status || '」，却未提供任何运单号："
                + "无法回传平台、无法向买家举证、无法对账',"
                + "'OPEN',now() "
                + "from fulfillment_dropship_tracking t "
                + "cross join fulfillment_dropship_tracking_policy_effective pol "
                + "where t.active_leg = 'SUPPLIER' "
                + "and t.supplier_leg_status in ('SHIPPED','IN_TRANSIT','DELIVERED') "
                + "and btrim(t.supplier_tracking_no) = '' "
                + "and pol.require_tracking_no_on_shipped "
                + "and not exists (select 1 from fulfillment_dropship_tracking_exception e "
                + "where e.tracking_id = t.id and e.exception_kind = 'SUPPLIER_RUNNING_AWAY' "
                + "and e.status <> 'DISMISSED')");

    List<String> detected = new ArrayList<>();
    if (delayed > 0) {
      detected.add("DELAYED_SHIPMENT(" + delayed + ")");
    }
    if (lost > 0) {
      detected.add("PARCEL_LOST(" + lost + ")");
    }
    if (runningAway > 0) {
      detected.add("SUPPLIER_RUNNING_AWAY(" + runningAway + ")");
    }
    return new DropshipTrackingEvaluateResult(scanned, delayed + lost + runningAway, detected);
  }

  /** 异常清单工作台。 */
  public DropshipTrackingExceptionPage pageExceptions(DropshipTrackingExceptionQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (query.orderId() != null) {
      where.add("order_id = ?");
      binds.add(query.orderId());
    }
    if (notBlank(query.exceptionKind())) {
      where.add("exception_kind = ?");
      binds.add(exceptionKind(query.exceptionKind()).name());
    }
    if (notBlank(query.status())) {
      where.add("status = ?");
      binds.add(exceptionStatus(query.status()).name());
    }
    if (notBlank(query.severity())) {
      where.add("severity = ?");
      binds.add(query.severity().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.keyword())) {
      where.add("(order_no ilike ? or sales_order_no ilike ? or exception_no ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_tracking_exception" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<DropshipTrackingExceptionRow> items =
        sql.fetch(
                "select " + EXCEPTION_COLUMNS + " from fulfillment_dropship_tracking_exception"
                    + sqlWhere
                    + " order by case severity when 'CRITICAL' then 0 when 'WARNING' then 1 else 2 end,"
                    + " detected_at desc limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipTrackingService::exceptionRow);
    return new DropshipTrackingExceptionPage(items, total, pageNo, size);
  }

  /**
   * 判为误报（DISMISSED）。必须写明理由 —— 否则下次同类异常会被一并放过，
   * 而误报解封后重新逾期是**一条新事实**，会被重新检出（部分唯一索引刻意放行 DISMISSED）。
   */
  @Transactional
  public DropshipTrackingExceptionRow dismissException(
      UUID exceptionId, DropshipDismissInput input, UUID membershipId) {
    if (input == null || !notBlank(input.reason())) {
      throw bad("判为误报必须写明理由：没有理由的误报会让下一次同类异常也一并被放过");
    }
    String reason = input.reason();
    requireException(exceptionId);
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update fulfillment_dropship_tracking_exception set status = 'DISMISSED',"
            + "acknowledged_at = ?::timestamptz, acknowledged_by_membership_id = ?,"
            + "resolution_note = ? where id = ?",
        now,
        membershipId,
        reason.trim(),
        exceptionId);
    return exceptionRow(
        sql.fetchOne(
            "select " + EXCEPTION_COLUMNS + " from fulfillment_dropship_tracking_exception where id = ?",
            exceptionId));
  }

  // ================================================================ 处置

  /**
   * 处置一条异常（催供应商 / 改自营发货 / 发起退款）。
   *
   * <p>三个动作在本模块各做各的事：
   * <ul>
   *   <li><b>URGE_SUPPLIER</b>：催单次数 +1（催了几次要看得见）、落 disposition +
   *       task(SUPPLIER_URGE)；</li>
   *   <li><b>SWITCH_TO_SELF</b>：<b>active_leg 真的翻到 OWN</b>、供应商段落 ABANDONED
   *       <b>保留为凭据</b>、该跟踪上未决异常全部置 DISPOSED，落 disposition +
   *       task(SELF_FULFILL_SWITCH) 交给我方仓。这就是蓝图那句「异常必须可切换」；</li>
   *   <li><b>REQUEST_REFUND</b>：落 disposition（含金额与币种）+ task(REFUND_REQUEST)
   *       交给 11 售后。<b>本模块不代为退款</b>，只登记待办。</li>
   * </ul>
   */
  @Transactional
  public DropshipDispositionSaved dispose(
      UUID exceptionId, DropshipDisposeInput input, UUID membershipId) {
    if (input == null || !notBlank(input.actionKind())) {
      throw bad("请给出处置动作");
    }
    DropshipDispositionAction action = dispositionAction(input.actionKind());
    Record exception = requireException(exceptionId);
    Record tracking =
        requireTracking(exception.get("tracking_id", UUID.class));
    UUID tenant = currentTenant();
    OffsetDateTime now = OffsetDateTime.now();

    BigDecimal refundAmount = null;
    String refundCurrency = null;
    if (action == DropshipDispositionAction.REQUEST_REFUND) {
      if (input.refundAmount() == null || !notBlank(input.refundCurrencyCode())) {
        throw bad(
            "发起退款必须给出退款金额与币种：11 售后未交付，金额此刻由运营按实际成本填，"
                + "缺金额的退款待办到了下游还得回头找人补");
      }
      refundAmount = input.refundAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      refundCurrency = input.refundCurrencyCode().trim().toUpperCase(Locale.ROOT);
      if (refundAmount.signum() <= 0) {
        throw bad("退款金额必须大于 0");
      }
    }
    String note = nz(input.note()).trim();
    if (note.isEmpty()) {
      throw bad("处置必须写明依据：催什么、为什么改自营、退多少");
    }

    UUID dispositionId = UUID.randomUUID();
    sql.execute(
        "insert into fulfillment_dropship_disposition (id,tenant_id,disposition_no,exception_id,"
            + "tracking_id,order_id,order_no,action_kind,status,refund_amount,"
            + "refund_currency_code,target_system,note,created_by_membership_id,created_at,updated_at) "
            + "values (?,?," + no("DSP", "dropship_disposition_no_seq")
            + ",?,?,?,?,?,'PENDING',?,?,?,?,?,?::timestamptz,?::timestamptz)",
        dispositionId,
        tenant,
        exceptionId,
        tracking.get("id", UUID.class),
        tracking.get("order_id", UUID.class),
        tracking.get("order_no", String.class),
        action.name(),
        refundAmount,
        refundCurrency,
        action.targetSystem(),
        note,
        membershipId,
        now,
        now);

    // 异常本身进入「已确认」：处置动作挂在这条异常上，异常不再悬空
    sql.execute(
        "update fulfillment_dropship_tracking_exception set status = 'ACKNOWLEDGED',"
            + "acknowledged_at = ?::timestamptz, acknowledged_by_membership_id = ?,"
            + "resolution_note = ? where id = ?",
        now,
        membershipId,
        note,
        exceptionId);

    switch (action) {
      case URGE_SUPPLIER -> {
        sql.execute(
            "update fulfillment_dropship_tracking set urge_count = urge_count + 1,"
                + "last_urged_at = ?::timestamptz, version = version + 1,"
                + "updated_at = ?::timestamptz where id = ?",
            now,
            now,
            tracking.get("id", UUID.class));
        ensureTask(
            tracking,
            DropshipTaskKind.SUPPLIER_URGE,
            dispositionId,
            exceptionId,
            Map.of(
                "orderNo", tracking.get("order_no", String.class),
                "supplierCode", tracking.get("supplier_code", String.class),
                "urgedAt", now.toString(),
                "note", note),
            "UR-" + tracking.get("id", UUID.class) + "-" + (urgeCount(tracking) + 1));
        appendEvent(
            tracking.get("id", UUID.class),
            "DISPOSITION_URGE_SUPPLIER",
            tracking.get("active_leg", String.class),
            tracking.get("active_leg", String.class),
            tracking.get("supplier_leg_status", String.class),
            tracking.get("supplier_leg_status", String.class),
            tracking.get("own_leg_status", String.class),
            tracking.get("own_leg_status", String.class),
            "催供应商：" + note,
            membershipId);
      }
      case SWITCH_TO_SELF -> {
        String fromSupplier = tracking.get("supplier_leg_status", String.class);
        sql.execute(
            "update fulfillment_dropship_tracking set active_leg = 'OWN',"
                + "supplier_leg_status = 'ABANDONED', switched_to_own_at = ?::timestamptz,"
                + "switched_to_own_by_membership_id = ?, switch_reason = ?,"
                + "version = version + 1, updated_at = ?::timestamptz where id = ?",
            now,
            membershipId,
            note,
            now,
            tracking.get("id", UUID.class));
        // 该跟踪上其余未决异常一并结案：改自营之后，供应商段的迟到/丢件/跑单
        // 不再是我方的履约风险 —— 但**判定证据保留**（证据触发器不许改写）
        sql.execute(
            "update fulfillment_dropship_tracking_exception set status = 'DISPOSED',"
                + "acknowledged_at = coalesce(acknowledged_at, ?::timestamptz),"
                + "acknowledged_by_membership_id = coalesce(acknowledged_by_membership_id, ?),"
                + "resolution_note = '改自营发货：该跟踪生效段已切到我方段，供应商段异常不再适用'"
                + ", resolved_at = ?::timestamptz, resolved_by_membership_id = ? "
                + "where tracking_id = ? and status not in ('DISPOSED','DISMISSED')",
            now,
            membershipId,
            now,
            membershipId,
            tracking.get("id", UUID.class));
        ensureTask(
            tracking,
            DropshipTaskKind.SELF_FULFILL_SWITCH,
            dispositionId,
            exceptionId,
            Map.of(
                "orderNo", tracking.get("order_no", String.class),
                "salesOrderNo", tracking.get("sales_order_no", String.class),
                "switchedAt", now.toString(),
                "note", note),
            "SS-" + tracking.get("id", UUID.class));
        appendEvent(
            tracking.get("id", UUID.class),
            "DISPOSITION_SWITCH_TO_SELF",
            tracking.get("active_leg", String.class),
            "OWN",
            fromSupplier,
            "ABANDONED",
            tracking.get("own_leg_status", String.class),
            tracking.get("own_leg_status", String.class),
            "改自营发货：生效段切到我方段，供应商段 " + fromSupplier + " → ABANDONED（保留凭据）",
            membershipId);
      }
      case REQUEST_REFUND -> {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", tracking.get("order_no", String.class));
        payload.put("salesOrderNo", tracking.get("sales_order_no", String.class));
        payload.put("refundAmount", refundAmount.toPlainString());
        payload.put("refundCurrencyCode", refundCurrency);
        payload.put("supplierTrackingNo", tracking.get("supplier_tracking_no", String.class));
        payload.put("note", note);
        ensureTask(
            tracking,
            DropshipTaskKind.REFUND_REQUEST,
            dispositionId,
            exceptionId,
            payload,
            "RF-" + exceptionId);
        appendEvent(
            tracking.get("id", UUID.class),
            "DISPOSITION_REQUEST_REFUND",
            tracking.get("active_leg", String.class),
            tracking.get("active_leg", String.class),
            tracking.get("supplier_leg_status", String.class),
            tracking.get("supplier_leg_status", String.class),
            tracking.get("own_leg_status", String.class),
            tracking.get("own_leg_status", String.class),
            "发起退款 " + refundAmount + " " + refundCurrency + "（待办交给 11 售后）",
            membershipId);
      }
    }
    return new DropshipDispositionSaved(dispositionId, dispositionNo(dispositionId), "PENDING");
  }

  /** 处置状态流转（PENDING → SENT → ACKNOWLEDGED → COMPLETED / PENDING → CANCELLED）。 */
  @Transactional
  public DropshipDispositionRow changeDispositionStatus(
      UUID dispositionId, DropshipDispositionStatusInput input, UUID membershipId) {
    if (input == null || !notBlank(input.toStatus())) {
      throw bad("请给出目标状态");
    }
    DropshipDispositionStatus target = dispositionStatus(input.toStatus());
    Record row =
        sql.fetchOne(
            "select " + DISPOSITION_COLUMNS + " from fulfillment_dropship_disposition where id = ?",
            dispositionId);
    if (row == null) {
      throw notFound("处置记录不存在或不属于当前租户");
    }
    OffsetDateTime now = OffsetDateTime.now();
    String outcome = nz(input.outcome()).trim();
    sql.execute(
        "update fulfillment_dropship_disposition set status = ?,"
            + "sent_at = case when ? = 'SENT' then ?::timestamptz else sent_at end,"
            + "acknowledged_at = case when ? = 'ACKNOWLEDGED' then ?::timestamptz"
            + " else acknowledged_at end,"
            + "completed_at = case when ? = 'COMPLETED' then ?::timestamptz"
            + " else completed_at end,"
            + "cancelled_at = case when ? = 'CANCELLED' then ?::timestamptz"
            + " else cancelled_at end,"
            + "outcome = case when ? <> '' then ? else outcome end,"
            + "updated_at = ?::timestamptz where id = ?",
        target.name(),
        target.name(),
        now,
        target.name(),
        now,
        target.name(),
        now,
        target.name(),
        now,
        outcome,
        outcome,
        now,
        dispositionId);
    return dispositionRow(
        sql.fetchOne(
            "select " + DISPOSITION_COLUMNS + " from fulfillment_dropship_disposition where id = ?",
            dispositionId));
  }

  // ================================================================ 任务队列

  /** 待回传 / 待处置任务工作台（下游 2.5.1.1 与 11 售后从这里接手）。 */
  public DropshipTaskPage pageTasks(DropshipTaskQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.taskKind())) {
      where.add("task_kind = ?");
      binds.add(taskKind(query.taskKind()).name());
    }
    if (notBlank(query.taskStatus())) {
      where.add("task_status = ?");
      binds.add(taskStatus(query.taskStatus()).name());
    }
    if (notBlank(query.targetSystem())) {
      where.add("target_system = ?");
      binds.add(query.targetSystem().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.keyword())) {
      where.add("(task_no ilike ? or order_no ilike ? or sales_order_no ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne("select count(*) c from fulfillment_dropship_task" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<DropshipTaskRow> items =
        sql.fetch(
                "select " + TASK_COLUMNS + " from fulfillment_dropship_task" + sqlWhere
                    + " order by created_at desc limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(this::taskRow);
    return new DropshipTaskPage(items, total, pageNo, size);
  }

  /** 下游领取任务（OPEN → CLAIMED），并记一次尝试。 */
  @Transactional
  public DropshipTaskSaved claimTask(UUID taskId, UUID membershipId) {
    Record row = requireTask(taskId);
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update fulfillment_dropship_task set task_status = 'CLAIMED',"
            + "claimed_by_membership_id = ?, claimed_at = ?::timestamptz,"
            + "attempt_count = attempt_count + 1, last_attempt_at = ?::timestamptz,"
            + "last_error = '', updated_at = ?::timestamptz"
            + " where id = ? and task_status = 'OPEN'",
        membershipId,
        now,
        now,
        now,
        taskId);
    var after =
        sql.fetchOne(
            "select " + TASK_COLUMNS + " from fulfillment_dropship_task where id = ?", taskId);
    return new DropshipTaskSaved(
        taskId,
        after.get("task_no", String.class),
        row.get("task_status", String.class));
  }

  /**
   * 下游回执（CLAIMED → ACKNOWLEDGED 或 FAILED）。
   *
   * <p>只有走到这里，{@code callback_status} 才允许从 PENDING/QUEUED 变成
   * SENT/ACKNOWLEDGED —— 也就是说「已回传平台」必须有一个下游真的受理过它的人，
   * 否则就只能是待办。
   */
  @Transactional
  public DropshipTaskSaved completeTask(
      UUID taskId, DropshipTaskResultInput input, UUID membershipId) {
    Record row = requireTask(taskId);
    boolean success = input != null && Boolean.TRUE.equals(input.success());
    OffsetDateTime now = OffsetDateTime.now();
    String error = nz(input == null ? null : input.error()).trim();
    if (!success && error.isEmpty()) {
      throw bad("标记失败必须写明原因：没有原因的失败只会被无限重试");
    }
    sql.execute(
        "update fulfillment_dropship_task set task_status = ?, last_error = ?,"
            + "attempt_count = attempt_count + 1, last_attempt_at = ?::timestamptz,"
            + "finished_at = case when ? then ?::timestamptz else finished_at end,"
            + "claimed_by_membership_id = coalesce(claimed_by_membership_id, ?),"
            + "claimed_at = coalesce(claimed_at, ?::timestamptz), updated_at = ?::timestamptz"
            + " where id = ?",
        success ? "ACKNOWLEDGED" : "FAILED",
        success ? "" : error,
        now,
        success,
        now,
        membershipId,
        now,
        now,
        taskId);

    if ("PLATFORM_CALLBACK".equals(row.get("task_kind", String.class))) {
      if (success) {
        sql.execute(
            "update fulfillment_dropship_tracking set callback_status = 'ACKNOWLEDGED',"
                + "callback_sent_at = coalesce(callback_sent_at, ?::timestamptz),"
                + "callback_last_error = '', version = version + 1, updated_at = ?::timestamptz"
                + " where id = ?",
            now,
            now,
            row.get("tracking_id", UUID.class));
      } else {
        sql.execute(
            "update fulfillment_dropship_tracking set callback_status = 'FAILED',"
                + "callback_last_error = ?, version = version + 1, updated_at = ?::timestamptz"
                + " where id = ?",
            error,
            now,
            row.get("tracking_id", UUID.class));
      }
    }
    var after =
        sql.fetchOne(
            "select " + TASK_COLUMNS + " from fulfillment_dropship_task where id = ?", taskId);
    return new DropshipTaskSaved(taskId, after.get("task_no", String.class),
        after.get("task_status", String.class));
  }

  // ================================================================ 事件流

  /** 事件流（只读；append-only，没有任何写形状）。 */
  public DropshipTrackingEventPage pageEvents(DropshipTrackingEventQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (query.trackingId() != null) {
      where.add("tracking_id = ?");
      binds.add(query.trackingId());
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_dropship_tracking_event" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<DropshipTrackingEventRow> items =
        sql.fetch(
                "select " + EVENT_COLUMNS + " from fulfillment_dropship_tracking_event" + sqlWhere
                    + " order by occurred_at desc limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(DropshipTrackingService::eventRow);
    return new DropshipTrackingEventPage(items, total, pageNo, size);
  }

  // ================================================================ 内部

  /** 有运单号且供应商段已发货 → 排一条回传平台任务（幂等，不重复造）。 */
  private void maybeEnsureCallbackTask(Record tracking, UUID membershipId) {
    String trackingNo = nz(tracking.get("supplier_tracking_no", String.class));
    String status = tracking.get("supplier_leg_status", String.class);
    if (trackingNo.isBlank() || !SHIPPED_SUPPLIER_STATUSES.contains(status)) {
      return;
    }
    sql.execute(
        "update fulfillment_dropship_tracking set callback_status = 'PENDING',"
            + "version = version + 1, updated_at = ?::timestamptz"
            + " where id = ? and callback_status = 'NOT_REQUIRED'",
        OffsetDateTime.now(),
        tracking.get("id", UUID.class));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("orderNo", tracking.get("order_no", String.class));
    payload.put("salesOrderNo", tracking.get("sales_order_no", String.class));
    payload.put("supplierTrackingNo", trackingNo);
    payload.put("supplierCarrierCode", nz(tracking.get("supplier_carrier_code", String.class)));
    payload.put("supplierShippedAt", String.valueOf(tracking.get("supplier_shipped_at", OffsetDateTime.class)));
    ensureTask(
        tracking,
        DropshipTaskKind.PLATFORM_CALLBACK,
        null,
        null,
        payload,
        "PC-" + tracking.get("id", UUID.class));
  }

  /** 落一条任务（ON CONFLICT DO NOTHING → 幂等键去重），返回任务 ID。 */
  private UUID ensureTask(
      Record tracking,
      DropshipTaskKind kind,
      UUID dispositionId,
      UUID exceptionId,
      Map<String, Object> payload,
      String idempotencyKey) {
    UUID tenant = currentTenant();
    String payloadJson;
    try {
      payloadJson = json.writeValueAsString(payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    sql.execute(
        "insert into fulfillment_dropship_task (id,tenant_id,task_no,task_kind,task_status,"
            + "tracking_id,order_id,order_no,sales_order_no,disposition_id,exception_id,"
            + "target_system,payload,idempotency_key,created_at,updated_at) "
            + "values (?,?," + no("DTK", "dropship_task_no_seq")
            + ",?,'OPEN',?,?,?,?,?,?,?,?::jsonb,?,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id,idempotency_key) do nothing",
        UUID.randomUUID(),
        tenant,
        kind.name(),
        tracking.get("id", UUID.class),
        tracking.get("order_id", UUID.class),
        tracking.get("order_no", String.class),
        tracking.get("sales_order_no", String.class),
        dispositionId,
        exceptionId,
        kind.targetSystem(),
        payloadJson,
        idempotencyKey,
        OffsetDateTime.now(),
        OffsetDateTime.now());
    var row =
        sql.fetchOne(
            "select id from fulfillment_dropship_task where idempotency_key = ?", idempotencyKey);
    if (row == null) {
      throw new IllegalStateException("任务落库后读不回来：idempotency_key=" + idempotencyKey);
    }
    return row.get("id", UUID.class);
  }

  private void appendEvent(
      UUID trackingId,
      String eventKind,
      String fromActiveLeg,
      String toActiveLeg,
      String fromSupplier,
      String toSupplier,
      String fromOwn,
      String toOwn,
      String detail,
      UUID membershipId) {
    sql.execute(
        "insert into fulfillment_dropship_tracking_event (id,tenant_id,tracking_id,event_kind,"
            + "from_active_leg,to_active_leg,from_supplier_leg_status,to_supplier_leg_status,"
            + "from_own_leg_status,to_own_leg_status,detail,actor_membership_id,occurred_at) "
            + "values (?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        trackingId,
        eventKind,
        nz(fromActiveLeg),
        nz(toActiveLeg),
        nz(fromSupplier),
        nz(toSupplier),
        nz(fromOwn),
        nz(toOwn),
        detail,
        membershipId,
        OffsetDateTime.now());
  }

  /** 取一条跟踪（RLS 下跨租户查不到 → 404）。 */
  private Record requireTracking(UUID trackingId) {
    if (trackingId == null) {
      throw bad("缺少 trackingId");
    }
    var row =
        sql.fetchOne(
            "select " + TRACKING_COLUMNS + " from fulfillment_dropship_tracking where id = ?",
            trackingId);
    if (row == null) {
      throw notFound("跟踪记录不存在或不属于当前租户");
    }
    return row;
  }

  private Record requireException(UUID exceptionId) {
    if (exceptionId == null) {
      throw bad("缺少 exceptionId");
    }
    var row =
        sql.fetchOne(
            "select " + EXCEPTION_COLUMNS + " from fulfillment_dropship_tracking_exception where id = ?",
            exceptionId);
    if (row == null) {
      throw notFound("履约异常不存在或不属于当前租户");
    }
    return row;
  }

  private Record requireTask(UUID taskId) {
    if (taskId == null) {
      throw bad("缺少 taskId");
    }
    var row =
        sql.fetchOne(
            "select " + TASK_COLUMNS + " from fulfillment_dropship_task where id = ?", taskId);
    if (row == null) {
      throw notFound("任务不存在或不属于当前租户");
    }
    return row;
  }

  private String trackingNo(UUID id) {
    return sql.fetchOne("select tracking_no from fulfillment_dropship_tracking where id = ?", id)
        .get("tracking_no", String.class);
  }

  private String dispositionNo(UUID id) {
    return sql.fetchOne("select disposition_no from fulfillment_dropship_disposition where id = ?", id)
        .get("disposition_no", String.class);
  }

  private int urgeCount(Record tracking) {
    Integer c = tracking.get("urge_count", Integer.class);
    return c == null ? 0 : c;
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid as t")
        .get("t", UUID.class);
  }

  /** 编号发号表达式（DB 生成，并发不撞号）。只接受内部常量，不接受外部输入。 */
  private static String no(String prefix, String sequence) {
    return "'" + prefix + "-' || to_char(now(),'YYYYMMDD') || '-'"
        + " || lpad(nextval('" + sequence + "')::text,6,'0')";
  }

  // ================================================================ 行映射

  private static DropshipTrackingRow trackingRow(Record r) {
    return new DropshipTrackingRow(
        r.get("id", UUID.class),
        r.get("tracking_no", String.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("sales_order_id", UUID.class),
        r.get("sales_order_no", String.class),
        r.get("supplier_code", String.class),
        r.get("active_leg", String.class),
        r.get("supplier_leg_status", String.class),
        r.get("supplier_carrier_code", String.class),
        r.get("supplier_tracking_no", String.class),
        r.get("supplier_shipped_at", OffsetDateTime.class),
        r.get("supplier_last_event_at", OffsetDateTime.class),
        r.get("supplier_delivered_at", OffsetDateTime.class),
        r.get("supplier_promised_ship_at", OffsetDateTime.class),
        r.get("supplier_exception_note", String.class),
        r.get("own_leg_status", String.class),
        r.get("own_carrier_code", String.class),
        r.get("own_tracking_no", String.class),
        r.get("own_handed_over_at", OffsetDateTime.class),
        r.get("own_last_event_at", OffsetDateTime.class),
        r.get("own_delivered_at", OffsetDateTime.class),
        r.get("own_delivery_note", String.class),
        r.get("switched_to_own_at", OffsetDateTime.class),
        r.get("switched_to_own_by_membership_id", UUID.class),
        r.get("switch_reason", String.class),
        r.get("urge_count", Integer.class),
        r.get("last_urged_at", OffsetDateTime.class),
        r.get("callback_status", String.class),
        r.get("callback_sent_at", OffsetDateTime.class),
        r.get("callback_attempts", Integer.class),
        r.get("callback_last_error", String.class),
        r.get("note", String.class),
        r.get("version", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static DropshipTrackingExceptionRow exceptionRow(Record r) {
    String kind = r.get("exception_kind", String.class);
    DropshipExceptionKind kindEnum = DropshipExceptionKind.valueOf(kind);
    return new DropshipTrackingExceptionRow(
        r.get("id", UUID.class),
        r.get("exception_no", String.class),
        r.get("tracking_id", UUID.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("sales_order_no", String.class),
        r.get("supplier_code", String.class),
        kind,
        kindEnum.label(),
        r.get("severity", String.class),
        r.get("rule_code", String.class),
        kindEnum.ruleSummary(),
        r.get("rule_threshold_hours", Integer.class),
        r.get("rule_evidence", String.class),
        r.get("status", String.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("acknowledged_by_membership_id", UUID.class),
        r.get("resolution_note", String.class),
        r.get("resolved_at", OffsetDateTime.class),
        r.get("resolved_by_membership_id", UUID.class),
        r.get("detected_at", OffsetDateTime.class));
  }

  private static DropshipDispositionRow dispositionRow(Record r) {
    String action = r.get("action_kind", String.class);
    DropshipDispositionAction actionEnum = DropshipDispositionAction.valueOf(action);
    return new DropshipDispositionRow(
        r.get("id", UUID.class),
        r.get("disposition_no", String.class),
        r.get("exception_id", UUID.class),
        r.get("tracking_id", UUID.class),
        r.get("order_no", String.class),
        action,
        actionEnum.label(),
        r.get("status", String.class),
        r.get("refund_amount", BigDecimal.class),
        r.get("refund_currency_code", String.class),
        r.get("target_system", String.class),
        r.get("note", String.class),
        r.get("created_by_membership_id", UUID.class),
        r.get("sent_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("outcome", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /**
   * 任务行。
   *
   * <p>payload 是 jsonb：jOOQ 读回来是 PGobject，<b>不会</b>自动变成 Map。
   * 直接 {@code get(..., Object.class)} 会拿到一个 PGobject，前端拿到的就是空对象
   * —— 而 payload 里正是「回传平台真正需要的运单号」这类字段，空掉等于
   * 下游接手时看不到要回传什么。所以这里显式按字符串取出再用 Jackson 解析。
   */
  private DropshipTaskRow taskRow(Record r) {
    DropshipTaskPayload map = readPayload(r.get("payload", String.class));
    return new DropshipTaskRow(
        r.get("id", UUID.class),
        r.get("task_no", String.class),
        r.get("task_kind", String.class),
        DropshipTaskKind.valueOf(r.get("task_kind", String.class)).label(),
        r.get("task_status", String.class),
        r.get("tracking_id", UUID.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("sales_order_no", String.class),
        r.get("disposition_id", UUID.class),
        r.get("exception_id", UUID.class),
        r.get("target_system", String.class),
        DropshipTaskKind.valueOf(r.get("task_kind", String.class)).targetOwner(),
        map,
        r.get("idempotency_key", String.class),
        r.get("attempt_count", Integer.class),
        r.get("last_attempt_at", OffsetDateTime.class),
        r.get("last_error", String.class),
        r.get("claimed_by_membership_id", UUID.class),
        r.get("claimed_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /** 解析任务载荷；解析不出就抛，而不是悄悄给一个空载荷（空载荷等于下游无从下手）。 */
  @SuppressWarnings("unchecked")
  private DropshipTaskPayload readPayload(String payloadText) {
    if (!notBlank(payloadText)) {
      return new DropshipTaskPayload(
          null, null, null, null, null, null, null, null, null, null, null);
    }
    try {
      return json.readValue(payloadText, DropshipTaskPayload.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("任务载荷不是合法的载荷对象：" + payloadText, e);
    }
  }

  private static DropshipTrackingEventRow eventRow(Record r) {
    return new DropshipTrackingEventRow(
        r.get("id", UUID.class),
        r.get("tracking_id", UUID.class),
        r.get("event_kind", String.class),
        r.get("from_active_leg", String.class),
        r.get("to_active_leg", String.class),
        r.get("from_supplier_leg_status", String.class),
        r.get("to_supplier_leg_status", String.class),
        r.get("from_own_leg_status", String.class),
        r.get("to_own_leg_status", String.class),
        r.get("detail", String.class),
        r.get("actor_membership_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 小工具与解析

  private static int pageSize(int size) {
    return size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
  }

  private static String whereClause(List<String> where) {
    return where.isEmpty() ? "" : " where " + String.join(" and ", where);
  }

  private static Object[] append(List<Object> binds, Object... extra) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(extra));
    return all.toArray();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static int intOr(Integer value, int fallback) {
    return value == null ? fallback : value;
  }

  private static DropshipTrackingLeg activeLeg(String value) {
    try {
      return DropshipTrackingLeg.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的生效段：" + value + "（SUPPLIER=供应商段，OWN=我方段）");
    }
  }

  private static DropshipSupplierLegStatus supplierLegStatus(String value) {
    try {
      return DropshipSupplierLegStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的供应商段状态：" + value);
    }
  }

  private static DropshipOwnLegStatus ownLegStatus(String value) {
    try {
      return DropshipOwnLegStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的我方段状态：" + value);
    }
  }

  private static DropshipExceptionKind exceptionKind(String value) {
    try {
      return DropshipExceptionKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的异常种类：" + value);
    }
  }

  private static DropshipExceptionStatus exceptionStatus(String value) {
    try {
      return DropshipExceptionStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的异常状态：" + value);
    }
  }

  private static DropshipDispositionAction dispositionAction(String value) {
    try {
      return DropshipDispositionAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad(
          "未知的处置动作："
              + value
              + "（URGE_SUPPLIER=催供应商，SWITCH_TO_SELF=改自营发货，REQUEST_REFUND=发起退款）");
    }
  }

  private static DropshipDispositionStatus dispositionStatus(String value) {
    try {
      return DropshipDispositionStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的处置状态：" + value);
    }
  }

  private static DropshipTaskKind taskKind(String value) {
    try {
      return DropshipTaskKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的任务种类：" + value);
    }
  }

  private static DropshipTaskStatus taskStatus(String value) {
    try {
      return DropshipTaskStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的任务状态：" + value);
    }
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}