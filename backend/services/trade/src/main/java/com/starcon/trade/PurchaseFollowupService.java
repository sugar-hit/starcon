package com.starcon.trade;

import static com.starcon.trade.PurchaseFollowupModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.3.2.1 跟单与交期（卷 06 采购与供应 / L3 6.3.2 采购跟踪的第一格）。
 *
 * <p>蓝图原话：「记录与供应商的沟通跟进（催单、确认、异常反馈），维护承诺到货日变更历史，
 * 统计平均延迟天数。」输入「跟单记录；新的承诺日期与原因。」输出「跟单时间线；交期变更历史；
 * 延迟统计。」关系「上游 = 6.3.1.1；下游 = 6.2.1.1（交期变化影响断货预测）、6.1.1.3 评级（准时率）。
 * <b>交期变更是断货风险的主要输入</b>，必须回写补货建议。」
 *
 * <p><b>本模块拥有两份举证凭据</b>：跟单记录（{@code purchase_followup_record}）与
 * 交期变更历史（{@code purchase_delivery_change}），加一张回写留痕（{@code purchase_delivery_writeback}）。
 *
 * <p><b>七条实现纪律</b>：
 *
 * <ol>
 *   <li><b>零 ALTER {@code purchase_order}</b>：那张表归 6.3.1.1 所有（V254 第 140 行的注释原文：
 *       「承诺到货日（对供应商的正式承诺；<b>6.3.2.1 跟单改的是它的历史</b>）」）。
 *       本模块<b>只 SELECT 它</b>，交期变更落在自己的历史表里。
 *       6.3.1.2（采购单创建与编辑）未交付 ⇒ 本仓<b>不存在</b>改单据承诺日的授权写入口，
 *       而 V254 的 {@code guard_purchase_order_status} 对 {@code CLOSED} 单据冻结了该列
 *       （对已关闭的单子改交期会被 DB 直接拒）。⇒ 本模块<b>不改</b>单据承诺日，
 *       改的是<b>跟单口径</b>，两个口径在响应里同时给出并标 {@code promiseDiverged}。</li>
 *   <li><b>「催单」是两个东西</b>：6.3.1.1 的 {@code POST /trade/purchase-orders/batch/urge}
 *       维护单据上的 {@code urge_count}（催过几次）；本模块的 {@code URGING} 是
 *       <b>一次具体的沟通</b>（谁、何时、说了什么、供应商怎么答的）。
 *       ⇒ 本模块<b>不写</b> {@code purchase_order.urge_count}，两张表也不合并。</li>
 *   <li><b>延迟天数是整数天，可以是负数</b>（提前到货）。口径 =
 *       <b>实际到货日 − 到货时刻生效的承诺日</b>；「到货时刻生效的承诺日」= 最后一条
 *       {@code changed_at <= 到货时刻} 的交期变更的 {@code to_promised_date}，没有则取单据承诺日。
 *       ⚠️ 多次改期时<b>不是</b>按原始承诺日算，也不是按最后一次变更算 ——
 *       供应商改期后按新日期到货是<b>准时</b>，按原始承诺日算会把守约说成违约。</li>
 *   <li><b>未到货的单子不算「延迟天数」</b>（延迟天数必须有终点），但另算「已逾期天数」。
 *       两个数分开给，不混成一个：把「还没到」算成「延迟 0」会让供应商看起来完美，
 *       把「还没到」算进平均延迟会让均值失真。</li>
 *   <li><b>回写所有权不越界</b>：{@code replenishment_suggestion*} 归 6.2.1.2、
 *       {@code stockout_risk_*} 归 6.2.1.1 ⇒ 本模块<b>零 INSERT / 零 UPDATE</b> 那些表，
 *       只<b>调用它们自己的服务方法</b>去触发重算（不是从 HTTP 打自己），
 *       并把<b>真实结果</b>（成功 / 失败 / 跳过）落在 {@code purchase_delivery_writeback}。</li>
 *   <li><b>回写失败不回滚交期变更</b>：交期变更是硬事实（供应商确实改了口），
 *       它必须先落库；回写是派生动作。所以交期变更与回写分成<b>两个独立事务</b>，
 *       回写失败只留一行 FAILED，不影响变更本身。</li>
 *   <li><b>时间绑定</b>：{@code timestamptz} 列用 {@link OffsetDateTime} 绑定，
 *       values 位置写 {@code ?::timestamptz}；{@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s。
 *       承诺到货日是 {@code date}（业务日历日，<b>不存时刻</b>）。</li>
 * </ol>
 */
@Service
public class PurchaseFollowupService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 备货周期上限（6.2.1.1 的 {@code leadTimeDays} 入参上限也是 365）。 */
  private static final int MAX_LEAD_TIME_DAYS = 365;

  /**
   * 租户默认覆盖天数的<b>兜底值</b>（只在租户没有配置 {@code scope='DEFAULT'} 的覆盖策略时用）。
   *
   * <p>6.2.1.2 的重算入口 {@code defaultCoverageDays} 是**必填**的（{@code @NotNull @Min(1) @Max(365)}），
   * 它的定位是「租户默认（配置表里没有 SKU / 分类配置时的兜底）」。
   * ⇒ 租户配了就用配置（下面先读 6.2.1.2 自己的配置表），没配就用这个值，
   * 并在 6.2.1.2 的批次里由它自己记下「覆盖天数 30 天（来源：计算时传入的兜底值）」——
   * <b>本模块不替租户编一个「看起来像配置」的值</b>，兜底值在批次里是自明的。</p>
   */
  private static final int FALLBACK_COVERAGE_DAYS = 30;

  /** 「今天」的数据库口径（UTC 日历日）。 */
  private static final String TODAY = "(now() at time zone 'UTC')::date";

  /**
   * 交付事实的公共 CTE 前缀。
   *
   * <p>四个 CTE，逐个对应一个口径：
   *
   * <ul>
   *   <li>{@code po} —— 每张采购单的两套承诺日：单据承诺日（6.3.1.1 所有）与跟单承诺日
   *       （本模块交期变更历史里最后一条的 {@code to_promised_date}，没有则等于单据承诺日）。</li>
   *   <li>{@code tracked} —— 6.3.2.2 的到货跟踪（<b>只读</b>），带「全部到货日」
   *       = 到货流水里 {@code arrived_at} 最晚那条。</li>
   *   <li>{@code arrived} —— 已<b>全部到货</b>的样本（部分到货的单还没到齐，不进延迟统计）。</li>
   *   <li>{@code effective} —— <b>到货时刻生效的承诺日</b>：最后一条
   *       {@code changed_at <= arrived_at} 的变更的 {@code to_promised_date}，没有则单据承诺日。</li>
   * </ul>
   */
  private static final String DELIVERY_CTE =
      "with po as ("
          + "  select o.id, o.tenant_id, o.order_no, o.supplier_ref_id, o.supplier_code, o.supplier_name,"
          + "         o.owner_user_id, o.owner_department_id, o.status,"
          + "         (o.created_at at time zone 'UTC')::date as created_date,"
          + "         o.promised_arrival_date as document_promise_date,"
          + "         coalesce((select c.to_promised_date from purchase_delivery_change c"
          + "                    where c.tenant_id = o.tenant_id and c.purchase_order_no = o.order_no"
          + "                    order by c.changed_at desc, c.id desc limit 1),"
          + "                  o.promised_arrival_date) as followup_promise_date"
          + "    from purchase_order o where o.tenant_id = ?::uuid"
          + "), tracked as ("
          + "  select t.purchase_order_no, t.arrival_status,"
          + "         (select max(r.arrived_at) from purchase_arrival_receipt r"
          + "           where r.tenant_id = t.tenant_id and r.tracking_id = t.id) as arrived_at"
          + "    from purchase_arrival_tracking t where t.tenant_id = ?::uuid"
          + "), arrived as ("
          + "  select tr.purchase_order_no, tr.arrived_at,"
          + "         (tr.arrived_at at time zone 'UTC')::date as arrived_date"
          + "    from tracked tr"
          + "   where tr.arrival_status = 'FULLY_RECEIVED' and tr.arrived_at is not null"
          + "), effective as ("
          + "  select a.purchase_order_no, a.arrived_date,"
          + "         coalesce((select c.to_promised_date from purchase_delivery_change c"
          + "                    where c.tenant_id = ?::uuid and c.purchase_order_no = a.purchase_order_no"
          + "                      and c.changed_at <= a.arrived_at"
          + "                    order by c.changed_at desc, c.id desc limit 1),"
          + "                  p.document_promise_date) as promise_at_arrival"
          + "    from arrived a join po p on p.order_no = a.purchase_order_no"
          + ") ";

  private final DSLContext sql;

  /** 6.2.1.1 缺货与断货风险（本模块的回写目标之一；**它拥有那两张表**）。 */
  private final StockoutRiskService stockout;

  /** 6.2.1.2 补货建议（本模块的回写目标之一；**它拥有那几张表**）。 */
  private final ReplenishmentSuggestionService replenishment;

  public PurchaseFollowupService(
      DSLContext sql, StockoutRiskService stockout, ReplenishmentSuggestionService replenishment) {
    this.sql = sql;
    this.stockout = stockout;
    this.replenishment = replenishment;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些维度真有依据、哪些下游接上了没有。
   *
   * <p>存在的理由与 6.3.1.1 / 6.2.1.1 一致：让界面能说清「为什么没有供应商评级」，
   * 而不是让它们显示成空白让人以为「没这个结论」。
   */
  public PurchaseFollowupCapabilities capabilities() {
    return new PurchaseFollowupCapabilities(
        List.of(
            "跟单记录 purchase_followup_record（追加式凭据）",
            "交期变更历史 purchase_delivery_change（from / to / 原因 / 操作人 / 时刻）",
            "单据承诺日 purchase_order.promised_arrival_date（6.3.1.1 所有，本模块只读）",
            "跟单承诺日 purchase_delivery_change 最后一条的 to_promised_date",
            "实际到货日 6.3.2.2 的 purchase_arrival_receipt.arrived_at（UTC 日历日）",
            "延迟天数（整数天，可为负 = 提前到货）",
            "已逾期天数（未全部到货且已逾期）",
            "跟单时间线（跟单记录 + 交期变更合并）",
            "回写留痕 purchase_delivery_writeback（成功 / 失败 / 跳过都留痕）"),
        List.of(
            new PurchaseFollowupCapability(
                "followup",
                "跟单记录与跟单时间线",
                true,
                null,
                null),
            new PurchaseFollowupCapability(
                "deliveryChange",
                "交期变更历史",
                true,
                null,
                null),
            new PurchaseFollowupCapability(
                "delayStats",
                "平均延迟天数 / 准时率",
                true,
                null,
                "以本模块的跟单与交期事实 + 6.3.2.2 的到货事实为口径"),
            new PurchaseFollowupCapability(
                "writebackStockout",
                "回写 6.2.1.1 断货风险",
                true,
                "6.2.1.1",
                "交期事实经本模块的「有效备货周期」进入 6.2.1.1 的快照重算（POST /trade/stockout-risk/snapshots）"),
            new PurchaseFollowupCapability(
                "writebackReplenishment",
                "回写 6.2.1.2 补货建议",
                true,
                "6.2.1.2",
                "触发 6.2.1.2 的批次重算（POST /trade/replenishment-suggestions/batches）；"
                    + "⚠️ 6.2.1.2 的供应商交期取自 catalog 报价与它自有的采购配置，"
                    + "本模块的交期事实**不能**参数化它（唯一入口 replenishment_order_policy 是人工策略，"
                    + "且其 upsert 会顶掉既有起订额），故回写的是**重算触发**而不是交期数值"),
            new PurchaseFollowupCapability(
                "supplierRating",
                "回写 6.1.1.3 供应商评级（准时率）",
                false,
                "6.1.1.3",
                "6.1.1.3 未交付 ⇒ 评级回写不存在。本模块自己算并输出准时率统计，"
                    + "但**不建**一张「供应商评级」表去冒充 6.1.1.3 的交付物")),
        List.of(
            "「当前承诺到货日」有两个口径：单据承诺日（6.3.1.1 所有）与跟单承诺日（本模块的变更历史）。"
                + "两者不一致时响应里 promiseDiverged=true，页面同时显示两个日期。",
            "本模块**不修改** purchase_order.promised_arrival_date：那张表归 6.3.1.1 所有，"
                + "且 6.3.1.2（采购单创建与编辑）未交付，本仓没有改单据承诺日的授权写入口。",
            "「催单」在本模块是**沟通记录**；6.3.1.1 的 batch/urge 是**单据上的催单次数**。两者不是一回事。",
            "延迟天数按**到货时刻生效的承诺日**算：供应商改期后按新日期到货算准时，不算违约。",
            "未到货的单子不进「平均延迟天数」（延迟天数必须有终点），另计「已逾期天数」。"));
  }

  // ================================================================ 跟单记录

  /**
   * 登记一条跟单记录。
   *
   * <p>采购单的存在性在<b>写入前</b>校验：跟单是「关于某张采购单的沟通」，
   * 没有采购单就无从跟单 —— 而且供应商快照要取自采购单（不是客户端传什么就存什么）。
   */
  public PurchaseFollowupRecordRow recordFollowup(
      PurchaseFollowupRecordInput input, UUID actorId, String scope, UUID actorDepartmentId) {
    Record order = requireOrder(input.purchaseOrderNo(), scope, actorId, actorDepartmentId);
    OffsetDateTime now = dbNow();
    if (input.occurredAt() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "沟通发生时刻不能为空");
    }
    // 补录是常态（可以补记昨天的沟通），但**未来时刻**的沟通是数据错误 ⇒ 拒。
    // 留 5 分钟时钟容差：客户端与服务端时钟不可能完全一致。
    if (input.occurredAt().isAfter(now.plusMinutes(5))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "沟通发生时刻不能晚于当前时间（最多允许 5 分钟时钟偏差）");
    }
    if (input.nextFollowupAt() != null && !input.nextFollowupAt().isAfter(input.occurredAt())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "下次跟进时刻必须晚于本次沟通发生时刻");
    }

    UUID id = UUID.randomUUID();
    long seq = sql.fetchOne("select nextval('purchase_followup_no_seq')").get(0, Long.class);
    String followupNo = "PF-" + seq;

    sql.execute(
        "insert into purchase_followup_record(id,tenant_id,followup_no,purchase_order_no,"
            + " purchase_order_ref_id,supplier_ref_id,supplier_code,supplier_name,followup_type,channel,"
            + " content,supplier_reply,occurred_at,next_followup_at,created_by,created_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::uuid,?::uuid,?::varchar,?::varchar,"
            + "?::varchar,?::varchar,?::varchar,?::varchar,?::timestamptz,?::timestamptz,?::uuid,now())",
        id,
        currentTenant(),
        followupNo,
        order.get("order_no", String.class),
        order.get("id", UUID.class),
        order.get("supplier_ref_id", UUID.class),
        order.get("supplier_code", String.class),
        order.get("supplier_name", String.class),
        input.followupType().name(),
        input.channel().name(),
        input.content().trim(),
        input.supplierReply() == null ? "" : input.supplierReply(),
        input.occurredAt(),
        input.nextFollowupAt(),
        actorId);

    return followupRow(id);
  }

  /** 跟单记录列表：真分页（{@code LIMIT ? OFFSET ?} + 独立的 {@code count(*)}）。 */
  public PurchaseFollowupRecordPage listFollowups(PurchaseFollowupRecordQuery q) {
    UUID tenantId = currentTenant();
    List<Object> binds = new ArrayList<>();
    String where = followupWhere(q, binds);
    long total =
        sql.fetchOne(
            "select count(*) from purchase_followup_record f"
                + " join purchase_order o on o.order_no = f.purchase_order_no"
                + " where o.tenant_id = ?::uuid" + where,
            bindList(tenantId, binds).toArray()).get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select f.*, o.promised_arrival_date as document_promise_date"
                + " from purchase_followup_record f"
                + " join purchase_order o on o.order_no = f.purchase_order_no"
                + " where o.tenant_id = ?::uuid" + where
                + " order by f.occurred_at desc, f.id desc limit ? offset ?",
            bindList(tenantId, binds, q.size(), q.page() * q.size()).toArray());
    List<PurchaseFollowupRecordRow> out = new ArrayList<>();
    for (Record r : rows) {
      out.add(followupRowOf(r));
    }
    return new PurchaseFollowupRecordPage(out, total, q.page(), q.size());
  }

  // ================================================================ 交期变更

  /**
   * 登记一次交期变更（**只落库**；回写由 {@link #writebackTo} 在**独立事务**里做）。
   *
   * <p>为什么分两段：交期变更是硬事实（供应商确实改了口），回写是派生动作。
   * 回写失败（上游 warehouse 不可达、租户没配覆盖天数……）**不能**把已发生的变更一起回滚掉。
   */
  public PurchaseFollowupChangeRow recordChange(
      PurchaseFollowupChangeInput input, UUID actorId, String scope, UUID actorDepartmentId) {
    Record order = requireOrder(input.purchaseOrderNo(), scope, actorId, actorDepartmentId);

    // 已关闭的单子不再改交期：交期是「这单还要多久到」的承诺，单子都关了它就没有意义了。
    // （V254 的 guard_purchase_order_status 对 CLOSED 单据冻结了 promised_arrival_date；
    //   本模块虽然不改那一列，但口径上也不该给已关闭的单子改期。）
    if ("CLOSED".equals(order.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "采购单已关闭，不能再变更交期（已关闭的单据内容在 DB 层是冻结的）");
    }
    if (input.toPromisedDate().equals(input.fromPromisedDate())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "变更后的承诺到货日与变更前相同 —— 那不是一次交期变更");
    }

    // 当前跟单承诺日 = 最后一次变更后的日期（没有变更过则等于单据承诺日）。
    LocalDate current = currentFollowupPromise(order);
    if (!current.equals(input.fromPromisedDate())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "变更前的承诺到货日与当前跟单承诺日不一致（当前为 " + current + "）"
              + " —— 交期历史必须连续，否则延迟天数会算到一个不存在的承诺上");
    }

    OffsetDateTime now = dbNow();
    OffsetDateTime changedAt = input.changedAt() == null ? now : input.changedAt();
    if (changedAt.isAfter(now.plusMinutes(5))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "变更时刻不能晚于当前时间（最多允许 5 分钟时钟偏差）");
    }

    UUID id = UUID.randomUUID();
    long seq = sql.fetchOne("select nextval('purchase_delivery_change_no_seq')").get(0, Long.class);
    String changeNo = "PDC-" + seq;

    sql.execute(
        "insert into purchase_delivery_change(id,tenant_id,change_no,purchase_order_no,"
            + " purchase_order_ref_id,supplier_ref_id,supplier_code,supplier_name,"
            + " from_promised_date,to_promised_date,reason,changed_by,changed_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::uuid,?::uuid,?::varchar,?::varchar,"
            + "?::date,?::date,?::varchar,?::uuid,?::timestamptz)",
        id,
        currentTenant(),
        changeNo,
        order.get("order_no", String.class),
        order.get("id", UUID.class),
        order.get("supplier_ref_id", UUID.class),
        order.get("supplier_code", String.class),
        order.get("supplier_name", String.class),
        input.fromPromisedDate(),
        input.toPromisedDate(),
        input.reason().trim(),
        actorId,
        changedAt);

    return changeRow(id);
  }

  /** 交期变更历史：真分页。 */
  public PurchaseFollowupChangePage listChanges(PurchaseFollowupChangeQuery q) {
    UUID tenantId = currentTenant();
    List<Object> binds = new ArrayList<>();
    String where = changeWhere(q, binds);
    long total =
        sql.fetchOne(
            "select count(*) from purchase_delivery_change c"
                + " join purchase_order o on o.order_no = c.purchase_order_no"
                + " where o.tenant_id = ?::uuid" + where,
            bindList(tenantId, binds).toArray()).get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select c.*, o.promised_arrival_date as document_promise_date,"
                + " coalesce((select c2.to_promised_date from purchase_delivery_change c2"
                + "            where c2.tenant_id = c.tenant_id and c2.purchase_order_no = c.purchase_order_no"
                + "            order by c2.changed_at desc, c2.id desc limit 1), c.to_promised_date)"
                + "   as followup_promise_date"
                + " from purchase_delivery_change c"
                + " join purchase_order o on o.order_no = c.purchase_order_no"
                + " where o.tenant_id = ?::uuid" + where
                + " order by c.changed_at desc, c.id desc limit ? offset ?",
            bindList(tenantId, binds, q.size(), q.page() * q.size()).toArray());
    List<PurchaseFollowupChangeRow> out = new ArrayList<>();
    for (Record r : rows) {
      out.add(changeRowOf(r));
    }
    // 回写留痕一次性批量取（一页一条 SQL，不是 N+1）。
    attachWriteback(out);
    return new PurchaseFollowupChangePage(out, total, q.page(), q.size());
  }

  // ================================================================ 时间线

  /** 跟单时间线：跟单记录 + 交期变更**合并**成一条按时间倒序的流（真分页）。 */
  public PurchaseFollowupTimelinePage timeline(PurchaseFollowupTimelineQuery q) {
    List<Object> binds = new ArrayList<>();
    String where = timelineWhere(q, binds);
    // 数据范围：ALL / DEPARTMENT / SELF（不接受请求参数传入）。
    // 它必须落在**两个分支各自**的 where 上（union 外面套一层没法引用 o）。
    List<Object> scopeBinds = new ArrayList<>();
    String scope =
        scopeClause(q.dataScope(), q.actorId(), q.actorDepartmentId(), "o", scopeBinds);
    String scopeSql = " and " + scope;
    String union =
        "select 'FOLLOWUP' as kind, f.id as id, f.followup_no as reference_no,"
            + " f.purchase_order_no as purchase_order_no, f.supplier_ref_id as supplier_ref_id,"
            + " f.supplier_code as supplier_code,"
            + " f.supplier_name as supplier_name, f.occurred_at as occurred_at,"
            + " f.created_by as actor_id, f.followup_type as sub_kind, f.content as detail,"
            + " null::date as from_promised_date, null::date as to_promised_date"
            + " from purchase_followup_record f"
            + " join purchase_order o on o.order_no = f.purchase_order_no"
            + " where o.tenant_id = ?::uuid" + scopeSql
            + " union all"
            + " select 'DELIVERY_CHANGE' as kind, c.id as id, c.change_no as reference_no,"
            + " c.purchase_order_no as purchase_order_no, c.supplier_ref_id as supplier_ref_id,"
            + " c.supplier_code as supplier_code,"
            + " c.supplier_name as supplier_name, c.changed_at as occurred_at,"
            + " c.changed_by as actor_id, null as sub_kind, c.reason as detail,"
            + " c.from_promised_date as from_promised_date, c.to_promised_date as to_promised_date"
            + " from purchase_delivery_change c"
            + " join purchase_order o on o.order_no = c.purchase_order_no"
            + " where o.tenant_id = ?::uuid" + scopeSql;
    List<Object> unionBinds = new ArrayList<>();
    unionBinds.add(currentTenant());
    unionBinds.addAll(scopeBinds);
    unionBinds.add(currentTenant());
    unionBinds.addAll(scopeBinds);

    long total =
        sql.fetchOne(
                "select count(*) from (" + union + ") t" + where, concat(unionBinds, binds).toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select * from (" + union + ") t" + where
                + " order by occurred_at desc, id desc limit ? offset ?",
            concat(unionBinds, binds, q.size(), q.page() * q.size()).toArray());

    List<PurchaseFollowupTimelineEntry> out = new ArrayList<>();
    for (Record r : rows) {
      String kind = r.get("kind", String.class);
      String subKind = r.get("sub_kind", String.class);
      out.add(
          new PurchaseFollowupTimelineEntry(
              kind,
              "FOLLOWUP".equals(kind) ? "跟单记录" : "交期变更",
              r.get("id", UUID.class),
              r.get("reference_no", String.class),
              r.get("purchase_order_no", String.class),
              r.get("supplier_code", String.class),
              r.get("supplier_name", String.class),
              "FOLLOWUP".equals(kind) ? followupTypeLabel(subKind) : "交期变更",
              r.get("detail", String.class),
              r.get("from_promised_date", LocalDate.class),
              r.get("to_promised_date", LocalDate.class),
              r.get("occurred_at", OffsetDateTime.class),
              r.get("actor_id", UUID.class)));
    }
    return new PurchaseFollowupTimelinePage(out, total, q.page(), q.size());
  }

  // ================================================================ 延迟统计

  /**
   * 延迟统计汇总 + 逐单明细。
   *
   * @param onlyOverdue 只看已逾期的（未全部到货且已过跟单承诺日）
   * @param onlyDelayed 只看已到货且延迟天数 &gt; 0 的
   */
  public PurchaseFollowupDeliveryStats stats(
      UUID supplierRefId, String keyword, boolean onlyOverdue, boolean onlyDelayed) {
    DeliveryAggregates agg = aggregates(supplierRefId, keyword, onlyOverdue, onlyDelayed);

    // 有效备货周期 = 承诺交期天数（建单日 → 跟单承诺日）+ 延迟缓冲（正向部分）。
    // ⚠️ 延迟缓冲只取**正向**部分：供应商整体提前时把负数加进去会让备货周期变短，
    //   那会把「本来就够用的备货时间」削掉 —— 断货风险宁可偏高也不偏低。
    Integer effective = null;
    if (agg.averagePromiseDays() != null) {
      BigDecimal base = agg.averagePromiseDays().setScale(0, RoundingMode.HALF_UP);
      BigDecimal buffer =
          agg.averageDelayDays() == null
              ? BigDecimal.ZERO
              : agg.averageDelayDays().setScale(0, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
      effective =
          Math.min(
              MAX_LEAD_TIME_DAYS,
              Math.max(1, base.add(buffer).setScale(0, RoundingMode.HALF_UP).intValue()));
    }

    return new PurchaseFollowupDeliveryStats(
        agg.arrivedCount(),
        agg.averageDelayDays(),
        agg.onTimeCount(),
        agg.onTimeRate(),
        agg.pendingCount(),
        agg.overduePendingCount(),
        agg.averageOverdueDays(),
        agg.changedOrderCount(),
        agg.changeCount(),
        effective,
        "跟单承诺日 = purchase_delivery_change 最后一条变更的 to_promised_date（无变更则取"
            + " purchase_order.promised_arrival_date）。单据承诺日另由 6.3.1.1 持有，两者不一致时"
            + " promiseDiverged=true。",
        "延迟天数 = 实际到货日（6.3.2.2 到货流水的 arrived_at 的 UTC 日历日）− 到货时刻生效的承诺日，"
            + "整数天，可为负（提前到货）。未全部到货的单子不进本口径，另计「已逾期天数」。",
        new PurchaseFollowupRatingWriteback(
            false,
            "6.1.1.3",
            "6.1.1.3 供应商评级未交付 ⇒ 评级回写不存在。本模块输出准时率统计，"
                + "但不建「供应商评级」表去冒充 6.1.1.3 的交付物。",
            "准时率 = 准时（延迟天数 ≤ 0）的单数 ÷ 已全部到货的单数。"));
  }

  /** 逐单交付实况（真分页）。 */
  public PurchaseFollowupDeliveryPage deliveryDetail(
      UUID supplierRefId, String keyword, boolean onlyOverdue, boolean onlyDelayed, int page, int size) {
    UUID tenantId = currentTenant();
    List<Object> binds = new ArrayList<>();
    String where = deliveryWhere(supplierRefId, keyword, onlyOverdue, onlyDelayed, binds);
    long total =
        sql.fetchOne(
            DELIVERY_CTE
                + "select count(*) from po p"
                + " left join tracked tr on tr.purchase_order_no = p.order_no"
                + " left join effective e on e.purchase_order_no = p.order_no"
                + " where p.supplier_ref_id is not null" + where,
            bindList(tenantId, tenantId, tenantId, binds).toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            DELIVERY_CTE
                + "select p.order_no as purchase_order_no, p.id as purchase_order_ref_id,"
                + " p.supplier_code as supplier_code, p.supplier_name as supplier_name,"
                + " p.document_promise_date as document_promise_date,"
                + " p.followup_promise_date as followup_promise_date,"
                + " tr.arrival_status as arrival_status, e.arrived_date as arrived_at,"
                + " case when e.arrived_date is not null"
                + "      then (e.arrived_date - e.promise_at_arrival) end as delay_days,"
                + " case when e.arrived_date is null and p.followup_promise_date < " + TODAY
                + "      then ((current_timestamp at time zone 'UTC')::date - p.followup_promise_date)"
                + " end as overdue_days,"
                + " (select count(*) from purchase_delivery_change c"
                + "   where c.tenant_id = p.tenant_id and c.purchase_order_no = p.order_no) as change_count,"
                + " (select count(*) from purchase_followup_record f"
                + "   where f.tenant_id = p.tenant_id and f.purchase_order_no = p.order_no) as followup_count,"
                + " (select max(f.occurred_at) from purchase_followup_record f"
                + "   where f.tenant_id = p.tenant_id and f.purchase_order_no = p.order_no) as last_followup_at"
                + " from po p"
                + " left join tracked tr on tr.purchase_order_no = p.order_no"
                + " left join effective e on e.purchase_order_no = p.order_no"
                + " where p.supplier_ref_id is not null" + where
                + " order by p.followup_promise_date asc, p.order_no asc limit ? offset ?",
            bindList(tenantId, tenantId, tenantId, binds, size, (long) page * size).toArray());
    List<PurchaseFollowupDeliveryRow> out = new ArrayList<>();
    for (Record r : rows) {
      LocalDate document = r.get("document_promise_date", LocalDate.class);
      LocalDate followup = r.get("followup_promise_date", LocalDate.class);
      out.add(
          new PurchaseFollowupDeliveryRow(
              r.get("purchase_order_no", String.class),
              r.get("purchase_order_ref_id", UUID.class),
              r.get("supplier_code", String.class),
              r.get("supplier_name", String.class),
              document,
              followup,
              document != null && followup != null && !document.equals(followup),
              r.get("arrival_status", String.class),
              r.get("arrived_at", LocalDate.class),
              r.get("delay_days", Integer.class),
              r.get("overdue_days", Integer.class),
              r.get("change_count", Integer.class) == null ? 0 : r.get("change_count", Integer.class),
              r.get("followup_count", Integer.class) == null
                  ? 0
                  : r.get("followup_count", Integer.class),
              r.get("last_followup_at", OffsetDateTime.class)));
    }
    return new PurchaseFollowupDeliveryPage(out, total, page, size);
  }

  // ================================================================ 回写

  /**
   * 把一次交期变更回写到指定的下游模块（**在调用方给定的那个事务里**）。
   *
   * <p>🔴 **写入所有权不越界**：本方法<b>不</b>写 {@code stockout_risk_*} 与
   * {@code replenishment_suggestion*}，而是调用**它们自己的服务方法**去触发重算
   * （6.2.1.1 的 {@code compute} / 6.2.1.2 的 {@code compute}）——
   * 写入行为与不变量仍然由原模块的代码负责，本模块只是**触发**它。
   *
   * <p>回写失败时**抛出**（让外层事务回滚、连带撤销下游的部分写入），
   * 由调用方在**另一个事务**里补一条 FAILED 留痕。
   */
  public PurchaseFollowupWritebackRow writebackTo(
      UUID changeId, String targetModule, UUID actorId, String bearer) {
    Record change = requireChange(changeId);
    if ("6.2.1.1".equals(targetModule)) {
      return writebackToStockout(change, actorId, bearer);
    }
    if ("6.2.1.2".equals(targetModule)) {
      return writebackToReplenishment(change, actorId, bearer);
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "未知的回写目标模块：" + targetModule);
  }

  /**
   * 回写 6.2.1.1 断货风险。
   *
   * <p>🔴 **交期 → 备货周期 → 断货风险**这条链是蓝图那句「交期变更是断货风险的主要输入」的落点：
   * 6.2.1.1 的快照入参 {@code leadTimeDays}（备货周期）就是交期口径，
   * 本模块用**跟单承诺日与实测延迟**算出这个数喂给它。
   *
   * <p>⚠️ **入参形状决定的限制（如实写进影响说明）**：6.2.1.1 的备货周期是**租户级**参数，
   * 不是逐 SKU 的 ⇒ 单张采购单的改期**无法**在该入口里按 SKU 精度表达。
   * 本模块传的是**租户级平均**（有效备货周期），并把这一点写进 {@code impact}。
   */
  private PurchaseFollowupWritebackRow writebackToStockout(
      Record change, UUID actorId, String bearer) {
    Integer leadTime = effectiveLeadTimeDays();
    if (leadTime == null) {
      return insertWriteback(
          change,
          "6.2.1.1",
          "POST /trade/stockout-risk/snapshots",
          false,
          false,
          "SKIPPED",
          "",
          "",
          "",
          "本租户还没有可用于推导备货周期的采购单事实（没有建单日 → 跟单承诺日的跨度），"
              + "本模块不替租户编一个备货周期。");
    }
    String note =
        "6.3.2.1 跟单与交期触发重算：采购单 "
            + change.get("purchase_order_no", String.class)
            + " 的承诺到货日由 "
            + change.get("from_promised_date", LocalDate.class)
            + " 变更为 "
            + change.get("to_promised_date", LocalDate.class)
            + "（有效备货周期 " + leadTime + " 天，来自跟单承诺日与实测延迟）";
    var result = stockout.compute(new StockoutRiskModels.StockoutComputeInput(null, leadTime, null, note), actorId, bearer);
    return insertWriteback(
        change,
        "6.2.1.1",
        "POST /trade/stockout-risk/snapshots",
        true,
        true,
        "SUCCEEDED",
        result.snapshotNo() == null ? "" : result.snapshotNo(),
        "断货风险快照 "
            + result.snapshotNo()
            + "：风险 "
            + result.itemCount()
            + " 项，紧急 "
            + result.criticalCount()
            + " 项，无数据 "
            + result.noDataCount()
            + " 项",
        "",
        "⚠️ 6.2.1.1 的备货周期是**租户级**入参（leadTimeDays），不是逐 SKU 的 ⇒ "
            + "单张采购单的改期在该入口里只能以租户级平均生效。");
  }

  /**
   * 回写 6.2.1.2 补货建议。
   *
   * <p>🔴 **这里做的是「重算触发」，不是「交期数值写入」**，如实记在留痕里：
   * 6.2.1.2 的供应商交期取自 <b>catalog 3.6.2 报价</b>（{@code lead_time_days}）与
   * 它<b>自有的</b>采购配置（{@code replenishment_order_policy.production_lead_time_days}）。
   * 本模块的交期事实**没有**合法的参数通道进到它那里 ——
   * 唯一能写交期的入口 {@code upsertOrderPolicy} 写的是**人工策略**，
   * 且它会**顶掉**既有那行（含人工维护的起订额与币种），
   * ⇒ 本模块**不自动写人工策略**，只触发重算并把这个限制如实报出。
   */
  private PurchaseFollowupWritebackRow writebackToReplenishment(
      Record change, UUID actorId, String bearer) {
    int coverageDays = defaultCoverageDays();
    boolean fromPolicy = coveragePolicyConfigured();
    String note =
        "6.3.2.1 跟单与交期触发重算：采购单 "
            + change.get("purchase_order_no", String.class)
            + " 的承诺到货日由 "
            + change.get("from_promised_date", LocalDate.class)
            + " 变更为 "
            + change.get("to_promised_date", LocalDate.class);
    var result =
        replenishment.compute(
            new ReplenishmentSuggestionModels.ReplenishmentComputeInput(null, coverageDays, note),
            actorId,
            bearer);
    return insertWriteback(
        change,
        "6.2.1.2",
        "POST /trade/replenishment-suggestions/batches",
        true,
        false,
        "SUCCEEDED",
        result.batchNo() == null ? "" : result.batchNo(),
        "补货建议批次 "
            + result.batchNo()
            + "：建议 "
            + result.itemCount()
            + " 项，需补货 "
            + result.replenishCount()
            + " 项，无需补 "
            + result.holdCount()
            + " 项，无销量数据 "
            + result.noSalesDataCount()
            + " 项",
        "",
        "⚠️ 6.2.1.2 的供应商交期取自 catalog 报价与它自有的采购配置，"
            + "本模块的交期事实**无法**参数化它（唯一入口 replenishment_order_policy 是人工策略，"
            + "其 upsert 会顶掉既有起订额）⇒ 本次回写是**重算触发**，交期数值**未**进入 6.2.1.2。"
            + "覆盖天数 "
            + coverageDays
            + " 天来自"
            + (fromPolicy ? "租户配置的默认覆盖策略。" : "调用方兜底值（租户未配置默认覆盖策略）。"));
  }

  /**
   * 记一条 FAILED 的回写留痕（**在失败之后的新事务里**调用）。
   *
   * <p>⚠️ 失败也必须留痕：「回写了没有」是可查的事实，不是一句返回值。
   */
  public PurchaseFollowupWritebackRow recordWritebackFailure(
      UUID changeId, String targetModule, RuntimeException error) {
    Record change = requireChange(changeId);
    String endpoint =
        "6.2.1.1".equals(targetModule)
            ? "POST /trade/stockout-risk/snapshots"
            : "POST /trade/replenishment-suggestions/batches";
    String reason = error == null ? "未知原因" : String.valueOf(error.getMessage());
    if (reason.length() > 1000) {
      reason = reason.substring(0, 1000);
    }
    return insertWriteback(
        change,
        targetModule,
        endpoint,
        false,
        false,
        "FAILED",
        "",
        "",
        reason,
        "本次回写未完成；交期变更本身**已落库**（交期变更是硬事实，回写是派生动作，"
            + "回写失败不回滚变更）。");
  }

  // ================================================================ 内部：查询与口径

  /** 读一张采购单（按当前数据范围），读不到就是「不存在或不在范围内」。 */
  private Record requireOrder(
      String orderNo, String scope, UUID actorId, UUID actorDepartmentId) {
    if (orderNo == null || orderNo.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "采购单号不能为空");
    }
    List<Object> binds = new ArrayList<>();
    binds.add(orderNo.trim());
    String clause = "";
    if ("DEPARTMENT".equals(scope) && actorDepartmentId != null) {
      clause = " and owner_department_id = ?::uuid";
      binds.add(actorDepartmentId);
    } else if ("SELF".equals(scope)) {
      clause = " and owner_user_id = ?::uuid";
      binds.add(actorId);
    }
    Record r =
        sql.fetchOne(
            "select id, order_no, status, supplier_ref_id, supplier_code, supplier_name,"
                + " promised_arrival_date, owner_user_id, owner_department_id"
                + " from purchase_order where tenant_id = ?::uuid and order_no = ?::varchar" + clause,
            bindList(currentTenant(), binds).toArray());
    if (r == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "采购单不存在或不在当前数据范围内：" + orderNo);
    }
    return r;
  }

  /** 当前跟单承诺日 = 最后一次交期变更后的日期（没有变更过则等于单据承诺日）。 */
  private LocalDate currentFollowupPromise(Record order) {
    Record last =
        sql.fetchOne(
            "select to_promised_date from purchase_delivery_change"
                + " where tenant_id = ?::uuid and purchase_order_no = ?::varchar"
                + " order by changed_at desc, id desc limit 1",
            currentTenant(),
            order.get("order_no", String.class));
    if (last == null) {
      return order.get("promised_arrival_date", LocalDate.class);
    }
    return last.get("to_promised_date", LocalDate.class);
  }

  private Record requireChange(UUID changeId) {    Record r =
        sql.fetchOne(
            "select id, change_no, purchase_order_no, from_promised_date, to_promised_date, reason"
                + " from purchase_delivery_change where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            changeId);
    if (r == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "交期变更不存在或不在当前租户内：" + changeId);
    }
    return r;
  }

  /** 写一条回写留痕。 */
  private PurchaseFollowupWritebackRow insertWriteback(
      Record change,
      String targetModule,
      String targetEndpoint,
      boolean available,
      boolean deliveryInputApplied,
      String status,
      String resultRef,
      String resultSummary,
      String errorMessage,
      String impact) {
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into purchase_delivery_writeback(id,tenant_id,change_id,target_module,target_endpoint,"
            + " owner_module,available,delivery_input_applied,status,result_ref,result_summary,"
            + " error_message,impact,attempted_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::boolean,?::boolean,"
            + "?::varchar,?::varchar,?::varchar,?::varchar,?::varchar,?::timestamptz)",
        id,
        currentTenant(),
        change.get("id", UUID.class),
        targetModule,
        targetEndpoint,
        targetModule,
        available,
        deliveryInputApplied,
        status,
        resultRef == null ? "" : resultRef,
        resultSummary == null ? "" : resultSummary,
        errorMessage == null ? "" : errorMessage,
        impact == null ? "" : impact,
        dbNow());
    return new PurchaseFollowupWritebackRow(
        id,
        targetModule,
        targetEndpoint,
        targetModule,
        available,
        deliveryInputApplied,
        status,
        resultRef == null ? "" : resultRef,
        resultSummary == null ? "" : resultSummary,
        errorMessage == null ? "" : errorMessage,
        impact == null ? "" : impact,
        dbNow());
  }

  /** 一次性把一页交期变更的回写留痕挂上去（一条 SQL，不是 N+1）。 */
  private void attachWriteback(List<PurchaseFollowupChangeRow> rows) {
    if (rows.isEmpty()) {
      return;
    }
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    StringBuilder placeholders = new StringBuilder();
    for (int i = 0; i < rows.size(); i++) {
      placeholders.append(i == 0 ? "?::uuid" : ",?::uuid");
      binds.add(rows.get(i).id());
    }
    Map<UUID, List<PurchaseFollowupWritebackRow>> grouped = new HashMap<>();
    for (Record w :
        sql.fetch(
            "select * from purchase_delivery_writeback where tenant_id = ?::uuid and change_id in ("
                + placeholders + ") order by target_module, attempted_at",
            binds.toArray())) {
      grouped
          .computeIfAbsent(w.get("change_id", UUID.class), k -> new ArrayList<>())
          .add(writebackRowOf(w));
    }
    List<PurchaseFollowupChangeRow> rebuilt = new ArrayList<>();
    for (PurchaseFollowupChangeRow r : rows) {
      rebuilt.add(
          new PurchaseFollowupChangeRow(
              r.id(),
              r.changeNo(),
              r.purchaseOrderNo(),
              r.purchaseOrderRefId(),
              r.supplierRefId(),
              r.supplierCode(),
              r.supplierName(),
              r.fromPromisedDate(),
              r.toPromisedDate(),
              r.reason(),
              r.changedBy(),
              r.changedAt(),
              r.followupPromiseDate(),
              r.documentPromiseDate(),
              r.promiseDiverged(),
              grouped.getOrDefault(r.id(), List.of())));
    }
    rows.clear();
    rows.addAll(rebuilt);
  }

  /** 聚合口径（一处算，多处用）。 */
  private record DeliveryAggregates(
      int arrivedCount,
      BigDecimal averageDelayDays,
      int onTimeCount,
      BigDecimal onTimeRate,
      int pendingCount,
      int overduePendingCount,
      BigDecimal averageOverdueDays,
      int changedOrderCount,
      int changeCount,
      BigDecimal averagePromiseDays) {}

  private DeliveryAggregates aggregates(
      UUID supplierRefId, String keyword, boolean onlyOverdue, boolean onlyDelayed) {
    List<Object> binds = new ArrayList<>();
    String where = deliveryWhere(supplierRefId, keyword, onlyOverdue, onlyDelayed, binds);
    Record r =
        sql.fetchOne(
            DELIVERY_CTE
                + "select count(*) filter (where e.arrived_date is not null) as arrived_count,"
                + " avg((e.arrived_date - e.promise_at_arrival)) filter (where e.arrived_date is not null)"
                + "   as average_delay_days,"
                + " count(*) filter (where e.arrived_date is not null"
                + "                   and (e.arrived_date - e.promise_at_arrival) <= 0) as on_time_count,"
                + " count(*) filter (where e.arrived_date is null) as pending_count,"
                + " count(*) filter (where e.arrived_date is null and p.followup_promise_date < " + TODAY
                + "   ) as overdue_pending_count,"
                + " avg((current_timestamp at time zone 'UTC')::date - p.followup_promise_date)"
                + "   filter (where e.arrived_date is null and p.followup_promise_date < " + TODAY
                + "   ) as average_overdue_days,"
                + " count(*) filter (where p.change_count > 0) as changed_order_count,"
                + " sum(p.change_count) as change_count,"
                + " avg(p.followup_promise_date - p.created_date) filter (where p.status <> 'CLOSED')"
                + "   as average_promise_days"
                + " from ("
                + "   select po.*, (select count(*) from purchase_delivery_change c"
                + "            where c.tenant_id = po.tenant_id and c.purchase_order_no = po.order_no)"
                + "     as change_count"
                + "     from po"
                + " ) p"
                + " left join tracked tr on tr.purchase_order_no = p.order_no"
                + " left join effective e on e.purchase_order_no = p.order_no"
                + " where p.supplier_ref_id is not null" + where,
            bindList(currentTenant(), currentTenant(), currentTenant(), binds).toArray());
    int arrived = r.get("arrived_count", Integer.class) == null ? 0 : r.get("arrived_count", Integer.class);
    int onTime = r.get("on_time_count", Integer.class) == null ? 0 : r.get("on_time_count", Integer.class);
    BigDecimal onTimeRate =
        arrived == 0
            ? null
            : BigDecimal.valueOf(onTime)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(arrived), 2, RoundingMode.HALF_UP);
    return new DeliveryAggregates(
        arrived,
        r.get("average_delay_days", BigDecimal.class),
        onTime,
        onTimeRate,
        r.get("pending_count", Integer.class) == null ? 0 : r.get("pending_count", Integer.class),
        r.get("overdue_pending_count", Integer.class) == null
            ? 0
            : r.get("overdue_pending_count", Integer.class),
        r.get("average_overdue_days", BigDecimal.class),
        r.get("changed_order_count", Integer.class) == null
            ? 0
            : r.get("changed_order_count", Integer.class),
        r.get("change_count", Long.class) == null ? 0 : r.get("change_count", Long.class).intValue(),
        r.get("average_promise_days", BigDecimal.class));
  }

  /**
   * 有效备货周期（回写 6.2.1.1 用）。
   *
   * <p>= 平均承诺交期天数（建单日 → 跟单承诺日，取未关闭的单）+ 延迟缓冲（平均延迟天数的正向部分）。
   * 没有采购单事实时返回 null（**不编一个数**）。
   */
  private Integer effectiveLeadTimeDays() {
    DeliveryAggregates agg = aggregates(null, null, false, false);
    if (agg.averagePromiseDays() == null) {
      return null;
    }
    BigDecimal base = agg.averagePromiseDays().setScale(0, RoundingMode.HALF_UP);
    BigDecimal buffer =
        agg.averageDelayDays() == null
            ? BigDecimal.ZERO
            : agg.averageDelayDays().setScale(0, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
    return Math.min(
        MAX_LEAD_TIME_DAYS,
        Math.max(1, base.add(buffer).setScale(0, RoundingMode.HALF_UP).intValue()));
  }

  /** 租户配置的默认覆盖天数（6.2.1.2 自己的配置表；**只读**）。没配则返回 null。 */
  private Integer configuredCoverageDays() {
    Record r =
        sql.fetchOne(
            "select coverage_days from replenishment_coverage_policy"
                + " where tenant_id = ?::uuid and scope = 'DEFAULT'"
                + "   and effective_from <= " + TODAY
                + "   and (effective_to is null or effective_to > " + TODAY + ")"
                + " order by effective_from desc limit 1",
            currentTenant());
    return r == null ? null : r.get("coverage_days", Integer.class);
  }

  private boolean coveragePolicyConfigured() {
    return configuredCoverageDays() != null;
  }

  private int defaultCoverageDays() {
    Integer configured = configuredCoverageDays();
    return configured == null ? FALLBACK_COVERAGE_DAYS : configured;
  }

  // ================================================================ 内部：行与条件

  private String followupWhere(PurchaseFollowupRecordQuery q, List<Object> binds) {
    List<String> where = new ArrayList<>();
    if (isSet(q.purchaseOrderNo())) {
      where.add("f.purchase_order_no = ?::varchar");
      binds.add(q.purchaseOrderNo().trim());
    }
    if (q.supplierRefId() != null) {
      where.add("f.supplier_ref_id = ?::uuid");
      binds.add(q.supplierRefId());
    }
    if (q.followupType() != null) {
      where.add("f.followup_type = ?::varchar");
      binds.add(q.followupType().name());
    }
    if (q.channel() != null) {
      where.add("f.channel = ?::varchar");
      binds.add(q.channel().name());
    }
    if (q.occurredFrom() != null) {
      where.add("f.occurred_at >= ?::timestamptz");
      binds.add(q.occurredFrom());
    }
    if (q.occurredTo() != null) {
      where.add("f.occurred_at <= ?::timestamptz");
      binds.add(q.occurredTo());
    }
    if (isSet(q.keyword())) {
      String pattern = "%" + q.keyword().trim() + "%";
      where.add(
          "(f.purchase_order_no ilike ?::varchar or f.supplier_code ilike ?::varchar"
              + " or f.supplier_name ilike ?::varchar or f.content ilike ?::varchar"
              + " or f.followup_no ilike ?::varchar)");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    // 数据范围：ALL / DEPARTMENT / SELF（不接受请求参数传入）。
    where.add(scopeClause(q.dataScope(), q.actorId(), q.actorDepartmentId(), "o", binds));
    return where.isEmpty() ? "" : " and " + String.join(" and ", where);
  }

  private String changeWhere(PurchaseFollowupChangeQuery q, List<Object> binds) {
    List<String> where = new ArrayList<>();
    if (isSet(q.purchaseOrderNo())) {
      where.add("c.purchase_order_no = ?::varchar");
      binds.add(q.purchaseOrderNo().trim());
    }
    if (q.supplierRefId() != null) {
      where.add("c.supplier_ref_id = ?::uuid");
      binds.add(q.supplierRefId());
    }
    if (q.changedFrom() != null) {
      where.add("c.changed_at >= ?::timestamptz");
      binds.add(q.changedFrom().atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
    }
    if (q.changedTo() != null) {
      where.add("c.changed_at < ?::timestamptz");
      binds.add(q.changedTo().plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
    }
    if (isSet(q.keyword())) {
      String pattern = "%" + q.keyword().trim() + "%";
      where.add(
          "(c.purchase_order_no ilike ?::varchar or c.supplier_code ilike ?::varchar"
              + " or c.supplier_name ilike ?::varchar or c.reason ilike ?::varchar"
              + " or c.change_no ilike ?::varchar)");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    where.add(scopeClause(q.dataScope(), q.actorId(), q.actorDepartmentId(), "o", binds));
    return where.isEmpty() ? "" : " and " + String.join(" and ", where);
  }

  private String timelineWhere(PurchaseFollowupTimelineQuery q, List<Object> binds) {
    List<String> where = new ArrayList<>();
    if (isSet(q.purchaseOrderNo())) {
      where.add("t.purchase_order_no = ?::varchar");
      binds.add(q.purchaseOrderNo().trim());
    }
    if (q.supplierRefId() != null) {
      where.add("t.supplier_ref_id = ?::uuid");
      binds.add(q.supplierRefId());
    }
    if (q.occurredFrom() != null) {
      where.add("t.occurred_at >= ?::timestamptz");
      binds.add(q.occurredFrom());
    }
    if (q.occurredTo() != null) {
      where.add("t.occurred_at <= ?::timestamptz");
      binds.add(q.occurredTo());
    }
    if (isSet(q.keyword())) {
      String pattern = "%" + q.keyword().trim() + "%";
      where.add(
          "(t.purchase_order_no ilike ?::varchar or t.supplier_code ilike ?::varchar"
              + " or t.supplier_name ilike ?::varchar or t.detail ilike ?::varchar"
              + " or t.reference_no ilike ?::varchar)");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    return where.isEmpty() ? "" : " where " + String.join(" and ", where);
  }

  private String deliveryWhere(
      UUID supplierRefId, String keyword, boolean onlyOverdue, boolean onlyDelayed,
      List<Object> binds) {
    List<String> where = new ArrayList<>();
    if (supplierRefId != null) {
      where.add("p.supplier_ref_id = ?::uuid");
      binds.add(supplierRefId);
    }
    if (isSet(keyword)) {
      String pattern = "%" + keyword.trim() + "%";
      where.add(
          "(p.order_no ilike ?::varchar or p.supplier_code ilike ?::varchar"
              + " or p.supplier_name ilike ?::varchar)");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    if (onlyOverdue) {
      where.add("e.arrived_date is null and p.followup_promise_date < " + TODAY);
    }
    if (onlyDelayed) {
      where.add("e.arrived_date is not null and (e.arrived_date - e.promise_at_arrival) > 0");
    }
    return where.isEmpty() ? "" : " and " + String.join(" and ", where);
  }

  /** 数据范围子句（ALL / DEPARTMENT / SELF）。不接受请求参数传入。 */
  private String scopeClause(
      String scope, UUID actorId, UUID actorDepartmentId, String alias, List<Object> binds) {
    if ("DEPARTMENT".equals(scope)) {
      if (actorDepartmentId == null) {
        throw new IllegalArgumentException("数据范围限定为部门时必须能解析出当前成员所属部门");
      }
      binds.add(actorDepartmentId);
      return alias + ".owner_department_id = ?::uuid";
    }
    if ("SELF".equals(scope)) {
      if (actorId == null) {
        throw new IllegalArgumentException("数据范围限定为本人时必须能解析出操作人");
      }
      binds.add(actorId);
      return alias + ".owner_user_id = ?::uuid";
    }
    return "true";
  }

  // ================================================================ 内部：行映射

  private PurchaseFollowupRecordRow followupRow(UUID id) {
    Record r =
        sql.fetchOne(
            "select f.*, o.promised_arrival_date as document_promise_date"
                + " from purchase_followup_record f"
                + " join purchase_order o on o.order_no = f.purchase_order_no"
                + " where f.tenant_id = ?::uuid and f.id = ?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "跟单记录不存在：" + id);
    }
    return followupRowOf(r);
  }

  private PurchaseFollowupRecordRow followupRowOf(Record r) {
    PurchaseFollowupType type =
        PurchaseFollowupType.valueOf(r.get("followup_type", String.class));
    PurchaseFollowupChannel channel =
        PurchaseFollowupChannel.valueOf(r.get("channel", String.class));
    return new PurchaseFollowupRecordRow(
        r.get("id", UUID.class),
        r.get("followup_no", String.class),
        r.get("purchase_order_no", String.class),
        r.get("purchase_order_ref_id", UUID.class),
        r.get("supplier_ref_id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("supplier_name", String.class),
        type,
        type.label(),
        channel,
        channel.label(),
        r.get("content", String.class),
        r.get("supplier_reply", String.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("next_followup_at", OffsetDateTime.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private PurchaseFollowupChangeRow changeRow(UUID id) {
    Record r =
        sql.fetchOne(
            "select c.*, o.promised_arrival_date as document_promise_date,"
                + " coalesce((select c2.to_promised_date from purchase_delivery_change c2"
                + "            where c2.tenant_id = c.tenant_id and c2.purchase_order_no = c.purchase_order_no"
                + "            order by c2.changed_at desc, c2.id desc limit 1), c.to_promised_date)"
                + "   as followup_promise_date"
                + " from purchase_delivery_change c"
                + " join purchase_order o on o.order_no = c.purchase_order_no"
                + " where c.tenant_id = ?::uuid and c.id = ?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "交期变更不存在：" + id);
    }
    PurchaseFollowupChangeRow row = changeRowOf(r);
    List<PurchaseFollowupWritebackRow> wbs = new ArrayList<>();
    for (Record w :
        sql.fetch(
            "select * from purchase_delivery_writeback where tenant_id = ?::uuid and change_id = ?::uuid"
                + " order by target_module",
            currentTenant(),
            id)) {
      wbs.add(writebackRowOf(w));
    }
    return new PurchaseFollowupChangeRow(
        row.id(),
        row.changeNo(),
        row.purchaseOrderNo(),
        row.purchaseOrderRefId(),
        row.supplierRefId(),
        row.supplierCode(),
        row.supplierName(),
        row.fromPromisedDate(),
        row.toPromisedDate(),
        row.reason(),
        row.changedBy(),
        row.changedAt(),
        row.followupPromiseDate(),
        row.documentPromiseDate(),
        row.promiseDiverged(),
        wbs);
  }

  private PurchaseFollowupWritebackRow writebackRowOf(Record w) {
    return new PurchaseFollowupWritebackRow(
        w.get("id", UUID.class),
        w.get("target_module", String.class),
        w.get("target_endpoint", String.class),
        w.get("owner_module", String.class),
        Boolean.TRUE.equals(w.get("available", Boolean.class)),
        Boolean.TRUE.equals(w.get("delivery_input_applied", Boolean.class)),
        w.get("status", String.class),
        w.get("result_ref", String.class),
        w.get("result_summary", String.class),
        w.get("error_message", String.class),
        w.get("impact", String.class),
        w.get("attempted_at", OffsetDateTime.class));
  }

  private PurchaseFollowupChangeRow changeRowOf(Record r) {
    LocalDate document = r.get("document_promise_date", LocalDate.class);
    LocalDate followup = r.get("followup_promise_date", LocalDate.class);
    return new PurchaseFollowupChangeRow(
        r.get("id", UUID.class),
        r.get("change_no", String.class),
        r.get("purchase_order_no", String.class),
        r.get("purchase_order_ref_id", UUID.class),
        r.get("supplier_ref_id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("supplier_name", String.class),
        r.get("from_promised_date", LocalDate.class),
        r.get("to_promised_date", LocalDate.class),
        r.get("reason", String.class),
        r.get("changed_by", UUID.class),
        r.get("changed_at", OffsetDateTime.class),
        followup,
        document,
        document != null && followup != null && !document.equals(followup),
        List.of());
  }

  private LocalDate latestFollowupPromise(String orderNo, LocalDate fallback) {
    Record last =
        sql.fetchOne(
            "select to_promised_date from purchase_delivery_change"
                + " where tenant_id = ?::uuid and purchase_order_no = ?::varchar"
                + " order by changed_at desc, id desc limit 1",
            currentTenant(),
            orderNo);
    return last == null ? fallback : last.get("to_promised_date", LocalDate.class);
  }

  // ================================================================ 内部：工具

  private OffsetDateTime dbNow() {
    return sql.fetchOne("select now()").get(0, OffsetDateTime.class);
  }

  /** 当前绑定的租户（由 tenancy 库在事务内设置，**不是**客户端头里的那个）。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid")
        .get(0, UUID.class);
  }

  private static boolean isSet(String v) {
    return v != null && !v.isBlank();
  }

  private static List<Object> bindList(Object head, List<Object> rest, Object... tail) {
    List<Object> all = new ArrayList<>();
    all.add(head);
    all.addAll(rest);
    all.addAll(java.util.Arrays.asList(tail));
    return all;
  }

  private static List<Object> bindList(
      Object head1, Object head2, Object head3, List<Object> rest, Object... tail) {
    List<Object> all = new ArrayList<>();
    all.add(head1);
    all.add(head2);
    all.add(head3);
    all.addAll(rest);
    all.addAll(java.util.Arrays.asList(tail));
    return all;
  }

  private static List<Object> concat(List<Object> head, List<Object> rest, Object... tail) {
    List<Object> all = new ArrayList<>(head);
    all.addAll(rest);
    all.addAll(java.util.Arrays.asList(tail));
    return all;
  }

  private static String followupTypeLabel(String raw) {
    if (raw == null) {
      return "跟单记录";
    }
    try {
      return PurchaseFollowupType.valueOf(raw).label();
    } catch (IllegalArgumentException e) {
      return "跟单记录";
    }
  }

  /** 供控制器复用的权限与范围常量。 */
  public static final String READ_PERMISSION = "trade.purchase.read";
  public static final String UPDATE_PERMISSION = "trade.purchase.update";
}
