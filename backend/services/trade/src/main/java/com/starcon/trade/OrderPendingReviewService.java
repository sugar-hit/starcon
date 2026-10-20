package com.starcon.trade;

import static com.starcon.trade.OrderPendingReviewModels.*;

import com.starcon.trade.OrderReviewRuleModels.ConditionDimension;
import com.starcon.trade.OrderReviewRuleModels.ConditionOperator;
import com.starcon.trade.OrderReviewRuleModels.HitCondition;
import com.starcon.trade.OrderReviewRuleModels.ReviewAction;
import com.starcon.trade.OrderReviewRuleModels.ReviewDecision;
import com.starcon.trade.OrderReviewRuleModels.ReviewDecisionType;
import com.starcon.trade.OrderReviewRuleModels.ReviewDetail;
import com.starcon.trade.OrderReviewRuleModels.ReviewMode;
import com.starcon.trade.OrderReviewRuleModels.ReviewRunInput;
import com.starcon.trade.OrderReviewRuleModels.RuleHit;
import com.starcon.trade.OrderRiskModels.RiskAssessment;
import com.starcon.trade.OrderRiskModels.RiskComponent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.2.1.1 待审队列：待审列表（按风险与时效排序）+ 批量通过/驳回/挂起。
 *
 * <p><b>八条实现纪律</b>：
 *
 * <ol>
 *   <li><b>队列成员不是一张复制出来的队列表</b>：它就是 {@code sales_order.status='SUBMITTED'}
 *       且没有终态审单结论的订单。平台单同步（9.1.2.1）落 SUBMITTED 即进队列，
 *       本模块不复制订单、也不改 {@code sales_order.status}（理由见 V209 文件头第一节）。</li>
 *   <li><b>「批量通过必须基于明确的规则，而不是默认全过」在服务层先拦一道</b>：
 *       通过动作必须带 {@code ruleCode + ruleVersion}；规则不存在 / 不在生效期 /
 *       动作是转人工 / 版本对不上，一律 400 显式拒绝，**没有兜底路径**。
 *       DB 层还有两道 CHECK 兜住绕过服务层的直写。</li>
 *   <li><b>通过必须真的由那条规则判出来</b>：逐单跑 9.2.1.2 的规则引擎，
 *       只有「判定结论是 AUTO_APPROVE <b>且</b> 决定结论的规则就是本次指名的那条」才通过。
 *       判成转人工的、或者命中的是别的规则的，一律跳过并写清原因。</li>
 *   <li><b>排序在 DB 层</b>：风险分（{@code order_risk_assessment.risk_score}）在前，
 *       时效分（{@code order_review_timeliness_score}，IMMUTABLE 函数）在后，
 *       再用提交时刻与 id 兜底保证分页不重不漏。前端拿到列表再排一次是错的 ——
 *       前端只能对当前页排序。</li>
 *   <li><b>「没量过」不是 0</b>：没有风险评估时 {@code riskScore} 是 null，
 *       排序时排在有分之后，绝不兜 0（0 分在风控语境里是「量过了，安全」）。</li>
 *   <li><b>每单命中的规则要能回溯到具体规则</b>：列表给命中的规则编号数组，
 *       详情给完整命中链（逐条件到订单字段与实际值），形状直接引用 9.2.1.2 的
 *       {@link RuleHit} / {@link HitCondition}，两套形状会让「回溯到规则 ID」断链。</li>
 *   <li><b>批量审单要幂等</b>：先占住幂等键再干活；重复提交同一个键直接回放上一次的回执，
 *       <b>不再改任何状态</b>。回执写明「本批实际通过/驳回/挂起/跳过各几条」，
 *       而不是「请求了几条」。</li>
 *   <li><b>时间一律显式绑定</b>：timestamptz 用 {@code ?::timestamptz} 绑定
 *       {@link OffsetDateTime}，绝不用 {@code Timestamp.valueOf(LocalDateTime...)}
 *       （那会静默偏 −28800s）。</li>
 * </ol>
 */
@Service
public class OrderPendingReviewService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单批最大条数。 */
  static final int MAX_BATCH_SIZE = 100;

  /** 详情里回看的批量记录条数。 */
  static final int MAX_RECENT_BATCHES = 10;

  /** 风险分达到这个值算「高风险」（概览面板用）。 */
  static final BigDecimal HIGH_RISK_THRESHOLD = new BigDecimal("60");

  private final DSLContext sql;
  private final OrderReviewRuleService rules;

  public OrderPendingReviewService(DSLContext sql, OrderReviewRuleService rules) {
    this.sql = sql;
    this.rules = rules;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：审单是发货前的最后人工闸门，「我这一步凭什么放行」必须能当面说清。
   * 哪些数据真的接通了、哪些没接通，由接口自己说，而不是让界面从空数组里猜。
   */
  public PendingReviewCapabilities capabilities() {
    UUID tenant = currentTenant();
    var counts =
        sql.fetchOne(
            "select count(*) filter (where status='ACTIVE' and action='AUTO_APPROVE' and forced_manual=false)"
                + " as auto_approve,"
                + "count(*) filter (where status='ACTIVE' and forced_manual) as forced,"
                + "count(*) filter (where status='ACTIVE') as active"
                + " from order_review_rule where tenant_id = ?",
            tenant);
    OffsetDateTime at = OffsetDateTime.now();

    return new PendingReviewCapabilities(
        List.of(
            new PendingReviewDimension(
                "ORDER_QUEUE", "待审订单（队列成员）", true,
                "sales_order（V99）+ 9.1.2.1 平台订单同步（V207）",
                "队列成员 = 已提交（status='SUBMITTED'）且没有终态审单结论的订单。"
                    + "平台单同步落 SUBMITTED 即进队列，本模块不复制订单。"),
            new PendingReviewDimension(
                "REVIEW_RULES", "审单规则配置", true,
                "order_review_rule（V149，9.2.1.2）",
                "规则、优先级、生效期、强制转人工标记全部可用；"
                    + "批量通过必须指名其中一条（ruleCode + ruleVersion）。"),
            new PendingReviewDimension(
                "REVIEW_HIT_CHAIN", "规则命中链", true,
                "order_review_rule_evaluation / _hit / _hit_condition（V149，9.2.1.2）",
                "逐条件到订单字段与实际值的完整命中链可回溯到具体规则 ID。"),
            new PendingReviewDimension(
                "RISK_ASSESSMENT", "风控风险分（排序键之一）", true,
                "order_risk_assessment（V150，9.2.2.2）",
                "取最近一次评估的 risk_score（0..100）。没有评估过的单 riskScore 为 null —— "
                    + "「没量过」不兜 0，兜 0 会把没评估的单排到最安全的那一档。"),
            new PendingReviewDimension(
                "RISK_SIGNALS", "风险信号明细", true,
                "order_risk_signal / order_risk_assessment_component（V150，9.2.2.2）",
                "五个维度恒为五行明细，缺信号的那行带着「为什么缺」。"),
            new PendingReviewDimension(
                "TIMELINESS", "时效分（排序键之一）", true,
                "sales_order.submitted_at + order_review_timeliness_score（V209）",
                "时效分与时效状态由 DB 的 IMMUTABLE 函数现算，可进 WHERE 与 ORDER BY；"
                    + "Java 与前端都不重算，避免两处实现漂移。")),
        List.of(
            new PendingReviewDownstream(
                "9.3", "履约编排", "trade", true,
                "审单结论落 order_review_queue_case：APPROVED 的单才是「可下发货」的，"
                    + "REJECTED 的单不进入履约。履约侧按 order_id 读结论即可。"),
            new PendingReviewDownstream(
                "9.3.3.1", "履约请求", "trade", true,
                "批量通过的直接下游：9.3.3.1 锁预占 → 生成出库单。"
                    + "本模块只产出结论，不预占库存（那属 07 仓储）。"),
            new PendingReviewDownstream(
                "9.1.2.1", "平台订单同步（上游）", "trade", true,
                "平台单落 sales_order.status='SUBMITTED' 即进本队列，"
                    + "本模块只读，不改订单单据状态。")),
        counts.get("active", Integer.class) == null ? 0 : counts.get("active", Integer.class),
        counts.get("auto_approve", Integer.class) == null ? 0 : counts.get("auto_approve", Integer.class),
        counts.get("forced", Integer.class) == null ? 0 : counts.get("forced", Integer.class),
        approvableRules(tenant, at),
        "审单是发货前的最后人工闸门：**批量通过必须指名一条具体规则（编号 + 版本）**，"
            + "规则缺失、规则不在生效期、规则动作是转人工，一律显式拒绝，没有「默认全过」这条路。"
            + "每一单的通过都必须由那条规则真的判出来（判定结论 AUTO_APPROVE 且决定结论的规则就是它）。"
            + "批量审单带幂等键：同一个键重复提交只生效一次，回执会写明本批实际通过/驳回/挂起各几条。");
  }

  /**
   * 可作为「批量通过」依据的规则清单：此刻生效中、动作是自动通过、不是强制转人工。
   *
   * <p>把这份清单放进能力声明，是为了让界面**只能从这几十行里挑** ——
   * 挑别的（转人工的、没生效的、版本对不上的）后端会显式拒绝，
   * 界面上提前一步就少一次往返。
   */
  private List<ApprovableRule> approvableRules(UUID tenant, OffsetDateTime at) {
    List<ApprovableRule> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select rule_code,rule_name,version,effective_from,effective_to from order_review_rule"
                + " where tenant_id = ? and status='ACTIVE' and action='AUTO_APPROVE'"
                + " and forced_manual = false and effective_from <= ?::timestamptz"
                + " and (effective_to is null or effective_to > ?::timestamptz)"
                + " order by priority desc, rule_code",
            tenant, at, at)) {
      out.add(
          new ApprovableRule(
              r.get("rule_code", String.class), r.get("rule_name", String.class),
              r.get("version", Long.class), r.get("effective_from", OffsetDateTime.class),
              r.get("effective_to", OffsetDateTime.class),
              "这条规则此刻生效中、动作是「自动通过」，可以作为批量通过的依据"));
    }
    return out;
  }

  // ================================================================ 概览

  /** 队列概览。不受列表筛选与分页影响 —— 它回答的是「我手上有多少活」。 */
  public PendingReviewSummary summary() {
    UUID tenant = currentTenant();
    OffsetDateTime at = OffsetDateTime.now();
    Record row =
        sql.fetchOne(
            "select count(*) filter (where c.id is null or c.status='SUSPENDED') as open_total,"
                + "count(*) filter (where c.id is null) as pending,"
                + "count(*) filter (where c.status='SUSPENDED') as suspended,"
                + "count(*) filter (where c.status='APPROVED') as approved,"
                + "count(*) filter (where c.status='REJECTED') as rejected,"
                + "count(*) filter (where (c.id is null or c.status='SUSPENDED')"
                + "   and order_review_timeliness_state(o.submitted_at, ?::timestamptz)='OVERDUE') as overdue,"
                + "count(*) filter (where (c.id is null or c.status='SUSPENDED') and r.id is null) as unevaluated,"
                + "count(*) filter (where (c.id is null or c.status='SUSPENDED')"
                + "   and r.risk_score is not null and r.risk_score >= 60) as high_risk"
                + " from sales_order o"
                + " left join order_review_queue_case c on c.tenant_id=o.tenant_id and c.order_id=o.id"
                + " left join lateral (select ra.id, ra.risk_score from order_risk_assessment ra"
                + "   where ra.tenant_id=o.tenant_id and ra.order_id=o.id"
                + "   order by ra.evaluated_reference_at desc, ra.created_at desc, ra.id desc limit 1) r on true"
                + " where o.tenant_id = ? and o.status='SUBMITTED'",
            at, tenant);
    return new PendingReviewSummary(
        row.get("open_total", Long.class), row.get("pending", Long.class),
        row.get("suspended", Long.class), row.get("approved", Long.class),
        row.get("rejected", Long.class), row.get("overdue", Long.class),
        row.get("unevaluated", Long.class), row.get("high_risk", Long.class), at);
  }

  // ================================================================ 列表

  /** 待审队列列表：真分页 + 真筛选 + **DB 层真排序**。 */
  public PendingReviewPage page(QueueQuery query) {
    UUID tenant = currentTenant();
    OffsetDateTime at = OffsetDateTime.now();
    int size = pageSize(query == null ? 0 : query.size());
    int page = query == null || query.page() < 0 ? 0 : query.page();

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("o.tenant_id = ?");
    binds.add(tenant);
    where.add("o.status = 'SUBMITTED'");

    // 队列状态筛选。默认（不给）= 未终结（待审 + 已挂起）：
    // 已挂起的单**仍在队列里等人**，藏起来就等于让它永远没人管。
    String status = query == null ? null : trim(query.queueStatus());
    if (status == null || status.isEmpty() || "OPEN".equalsIgnoreCase(status)) {
      where.add("(c.id is null or c.status = 'SUSPENDED')");
    } else if ("PENDING".equalsIgnoreCase(status)) {
      where.add("c.id is null");
    } else if ("SUSPENDED".equalsIgnoreCase(status)) {
      where.add("c.status = 'SUSPENDED'");
    } else if ("APPROVED".equalsIgnoreCase(status)) {
      where.add("c.status = 'APPROVED'");
    } else if ("REJECTED".equalsIgnoreCase(status)) {
      where.add("c.status = 'REJECTED'");
    } else if ("ALL".equalsIgnoreCase(status)) {
      // 全量：不加队列状态条件。
    } else {
      throw bad("队列状态只能是 PENDING / SUSPENDED / APPROVED / REJECTED / ALL");
    }

    if (query != null && notBlank(query.keyword())) {
      where.add("(o.order_no ilike '%' || ? || '%' or o.external_order_no ilike '%' || ? || '%')");
      binds.add(query.keyword().trim());
      binds.add(query.keyword().trim());
    }
    if (query != null && notBlank(query.orderSource())) {
      where.add("o.order_source = ?");
      binds.add(query.orderSource().trim().toUpperCase(Locale.ROOT));
    }
    if (query != null && notBlank(query.reviewDecision())) {
      where.add("e.decision = ?");
      binds.add(query.reviewDecision().trim().toUpperCase(Locale.ROOT));
    }
    if (query != null && query.riskEvaluated() != null) {
      where.add(query.riskEvaluated() ? "r.id is not null" : "r.id is null");
    }

    String clause = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) c from sales_order o"
                    + " left join order_review_queue_case c on c.tenant_id=o.tenant_id and c.order_id=o.id"
                    + " left join lateral (select ra.id from order_risk_assessment ra"
                    + "   where ra.tenant_id=o.tenant_id and ra.order_id=o.id"
                    + "   order by ra.evaluated_reference_at desc, ra.created_at desc, ra.id desc limit 1) r on true"
                    + " left join lateral (select ev.decision from order_review_rule_evaluation ev"
                    + "   where ev.tenant_id=o.tenant_id and ev.order_id=o.id"
                    + "   order by (ev.mode='RECORDED') desc, ev.evaluated_at desc, ev.created_at desc, ev.id desc"
                    + "   limit 1) e on true" + clause,
                binds.toArray())
            .get("c", Long.class);

    // ⚠️ 绑定顺序必须与 **SQL 文本里的问号出现顺序**一致：
    //   SELECT 列表里的三个 ?::timestamptz 出现在 WHERE 之前 ⇒ 它们先绑。
    //   顺序错了 PG 会拿 uuid 去 cast 成 timestamptz，报「cannot cast type uuid to timestamp」。
    List<Object> pageBinds = new ArrayList<>();
    pageBinds.add(at);
    pageBinds.add(at);
    pageBinds.add(at);
    pageBinds.addAll(binds);
    pageBinds.add(at);
    pageBinds.add(size);
    pageBinds.add(page * size);

    List<PendingReviewRow> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            queueSelect() + clause
                // ⚠️ 排序键全在 DB：风险分（高 → 低，没分的后排）→ 时效分（高 → 低），
                //   再用提交时刻与 id 兜底 —— 没有这两个兜底键，分页会在并发写入时重不漏地错乱。
                + " order by (r.risk_score is null) asc, r.risk_score desc nulls last,"
                + "   order_review_timeliness_score(o.submitted_at, ?::timestamptz) desc,"
                + "   o.submitted_at asc, o.id asc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(row(r));
    }
    return new PendingReviewPage(items, total, page, size,
        "排序键：风险分（order_risk_assessment.risk_score，越高越前；未评估的排在最后）"
            + " → 时效分（按提交至今的等候时长，越久越前）→ 提交时刻 → 订单 id。排序在数据库层完成。");
  }

  /** 列表主查询。命中链的规则编号用 array_agg 一次取回，不做 N+1。 */
  private String queueSelect() {
    return "select o.id, o.order_no, o.external_order_no, o.order_source, o.currency,"
        + " o.grand_total_amount, o.submitted_at,"
        + " extract(epoch from (?::timestamptz - o.submitted_at)) / 3600 as waiting_hours,"
        + " order_review_timeliness_score(o.submitted_at, ?::timestamptz) as timeliness_score,"
        + " order_review_timeliness_state(o.submitted_at, ?::timestamptz) as timeliness_state,"
        + " c.status as queue_status, c.decided_at, c.decision_reason, c.rule_code, c.rule_version,"
        + " c.batch_id,"
        + " r.risk_score, r.recommended_action as risk_recommended_action,"
        + " r.manual_review_required as risk_manual_review_required,"
        + " r.evaluation_status as risk_evaluation_status,"
        + " e.decision as review_decision, e.mode as review_mode,"
        + " e.decision_reason as review_decision_reason, e.winning_rule_code, e.winning_rule_name,"
        + " e.matched_rule_count, e.forced_rule_count, e.address_anomaly_codes,"
        + " (select array_agg(h.rule_code order by h.eval_rank) from order_review_rule_hit h"
        + "   where h.tenant_id = e.tenant_id and h.evaluation_id = e.id and h.matched) as hit_rule_codes,"
        + " a.recipient_name, a.country_code"
        + " from sales_order o"
        + " left join order_review_queue_case c on c.tenant_id=o.tenant_id and c.order_id=o.id"
        + " left join lateral (select ra.risk_score, ra.recommended_action, ra.manual_review_required,"
        + "   ra.evaluation_status from order_risk_assessment ra"
        + "   where ra.tenant_id=o.tenant_id and ra.order_id=o.id"
        + "   order by ra.evaluated_reference_at desc, ra.created_at desc, ra.id desc limit 1) r on true"
        + " left join lateral (select ev.id, ev.tenant_id, ev.mode, ev.decision, ev.decision_reason,"
        + "   ev.winning_rule_code, ev.winning_rule_name, ev.matched_rule_count, ev.forced_rule_count,"
        + "   ev.address_anomaly_codes from order_review_rule_evaluation ev"
        + "   where ev.tenant_id=o.tenant_id and ev.order_id=o.id"
        + "   order by (ev.mode='RECORDED') desc, ev.evaluated_at desc, ev.created_at desc, ev.id desc"
        + "   limit 1) e on true"
        + " left join order_address_snapshot a on a.tenant_id=o.tenant_id and a.order_id=o.id"
        + "   and a.address_role='SHIPPING'";
  }

  // ================================================================ 详情

  /** 单条详情：订单事实 + 当前结论 + 风险明细 + 完整命中链 + 最近几次批量结果。 */
  public PendingReviewDetail detail(UUID orderId) {
    UUID tenant = currentTenant();
    OffsetDateTime at = OffsetDateTime.now();

    Record order =
        sql.fetchOne(
            "select o.id,o.order_no,o.external_order_no,o.order_source,o.manual_kind,o.currency,"
                + " o.subtotal_amount,o.discount_amount,o.tax_amount,o.shipping_amount,o.other_amount,"
                + " o.grand_total_amount,o.submitted_at,o.status,"
                + " a.recipient_name,a.recipient_phone,a.recipient_email,a.country_code,a.city,"
                + " a.postcode,a.address_line1,"
                + " (select count(*) from sales_order_line l where l.tenant_id=o.tenant_id"
                + "   and l.order_id=o.id) as line_count,"
                + " (select coalesce(sum(l.quantity),0) from sales_order_line l where l.tenant_id=o.tenant_id"
                + "   and l.order_id=o.id) as total_qty"
                + " from sales_order o"
                + " left join order_address_snapshot a on a.tenant_id=o.tenant_id and a.order_id=o.id"
                + "   and a.address_role='SHIPPING'"
                + " where o.tenant_id = ? and o.id = ?",
            tenant, orderId);
    if (order == null) {
      throw notFound("订单不存在或不属于当前租户（RLS 下查不到即视为不存在）");
    }

    Record c =
        sql.fetchOne(
            "select status,enqueued_at,decided_at,decided_by_membership_id,decision_reason,"
                + " rule_code,rule_version,evaluation_id,batch_id"
                + " from order_review_queue_case where tenant_id = ? and order_id = ?",
            tenant, orderId);
    QueueCaseInfo caseInfo = null;
    if (c != null) {
      String status = c.get("status", String.class);
      caseInfo =
          new QueueCaseInfo(
              status, labelOf(status), c.get("enqueued_at", OffsetDateTime.class),
              c.get("decided_at", OffsetDateTime.class),
              c.get("decided_by_membership_id", UUID.class),
              nz(c.get("decision_reason", String.class)),
              c.get("rule_code", String.class), c.get("rule_version", Long.class),
              c.get("evaluation_id", UUID.class), c.get("batch_id", UUID.class));
    }

    // 风控：最近一次评估 + 五个维度的明细。
    RiskAssessment assessment = null;
    List<RiskComponent> components = List.of();
    Record a =
        sql.fetchOne(
            "select id,order_id,order_no,buyer_fingerprint,order_currency,order_amount,"
                + " order_submitted_at,rule_id,rule_name,rule_version,evaluated_reference_at,risk_score,"
                + " recommended_action,manual_review_required,evaluation_status,scored_dimension_count,"
                + " missing_dimension_count,final_action,reviewed_at,reviewed_by,totals_computed_at,"
                + " created_by,created_at from order_risk_assessment"
                + " where tenant_id = ? and order_id = ?"
                + " order by evaluated_reference_at desc, created_at desc, id desc limit 1",
            tenant, orderId);
    if (a != null) {
      assessment =
          new RiskAssessment(
              a.get("id", UUID.class), a.get("order_id", UUID.class), a.get("order_no", String.class),
              a.get("buyer_fingerprint", String.class), a.get("order_currency", String.class),
              a.get("order_amount", BigDecimal.class), a.get("order_submitted_at", OffsetDateTime.class),
              a.get("rule_id", UUID.class), a.get("rule_name", String.class),
              a.get("rule_version", Long.class), a.get("evaluated_reference_at", OffsetDateTime.class),
              a.get("risk_score", BigDecimal.class),
              OrderRiskModels.RecommendedAction.valueOf(a.get("recommended_action", String.class)),
              Boolean.TRUE.equals(a.get("manual_review_required", Boolean.class)),
              OrderRiskModels.EvaluationStatus.valueOf(a.get("evaluation_status", String.class)),
              a.get("scored_dimension_count", Integer.class),
              a.get("missing_dimension_count", Integer.class),
              a.get("final_action", String.class) == null
                  ? null
                  : OrderRiskModels.FinalAction.valueOf(a.get("final_action", String.class)),
              a.get("reviewed_at", OffsetDateTime.class), a.get("reviewed_by", UUID.class),
              a.get("totals_computed_at", OffsetDateTime.class), a.get("created_by", UUID.class),
              a.get("created_at", OffsetDateTime.class));
      components = riskComponents(tenant, assessment.id());
    }

    // 审单判定：优先生效判定（RECORDED），没有则取最近一次试跑（DRY_RUN）。
    ReviewDecision decision = null;
    List<RuleHit> hits = List.of();
    Record e =
        sql.fetchOne(
            "select id,order_id,order_no,order_source,currency_code,order_total,"
                + " discount_rate_percent,mode,evaluated_scope,decision,decision_reason,"
                + " winning_rule_id,winning_rule_code,winning_rule_name,considered_rule_count,"
                + " matched_rule_count,forced_rule_count,address_anomaly_codes,evaluated_at,"
                + " evaluated_by_membership_id,created_at from order_review_rule_evaluation"
                + " where tenant_id = ? and order_id = ?"
                + " order by (mode='RECORDED') desc, evaluated_at desc, created_at desc, id desc limit 1",
            tenant, orderId);
    if (e != null) {
      decision = reviewDecision(e);
      hits = hitChain(tenant, decision.id());
    }

    List<PendingReviewBatchRef> batches = recentBatches(tenant, orderId);

    PendingReviewRow row =
        row(
            sql.fetchOne(
                queueSelect() + " where o.tenant_id = ? and o.id = ?", at, at, at, tenant, orderId));

    return new PendingReviewDetail(
        row,
        new OrderSnapshot(
            order.get("id", UUID.class), order.get("order_no", String.class),
            nz(order.get("external_order_no", String.class)), order.get("order_source", String.class),
            nz(order.get("manual_kind", String.class)), order.get("currency", String.class),
            order.get("subtotal_amount", BigDecimal.class), order.get("discount_amount", BigDecimal.class),
            order.get("tax_amount", BigDecimal.class), order.get("shipping_amount", BigDecimal.class),
            order.get("other_amount", BigDecimal.class), order.get("grand_total_amount", BigDecimal.class),
            order.get("submitted_at", OffsetDateTime.class), order.get("status", String.class),
            order.get("recipient_name", String.class), order.get("recipient_phone", String.class),
            order.get("recipient_email", String.class), order.get("country_code", String.class),
            order.get("city", String.class), order.get("postcode", String.class),
            order.get("address_line1", String.class), order.get("line_count", Integer.class),
            order.get("total_qty", BigDecimal.class)),
        caseInfo,
        assessment,
        components,
        decision,
        hits,
        batches,
        "命中链逐条件给出「哪个条件、拿订单的哪个字段、实际值多少」，"
            + "可以直接原话念给买家听；风控分只是**建议**，最终动作只能由人给出。");
  }

  /**
   * 试算：按**此刻生效中**的规则跑一次判定，标成 DRY_RUN，不冒充生产结论。
   *
   * <p>委托 9.2.1.2 的判定引擎（本服务同库同服务，跨服务读才是越界）：
   * 规则命中链是 9.2.1.2 的主权，本模块只负责「把链摆到队列上给人看」。
   */
  public ReviewDetail evaluate(UUID orderId, OffsetDateTime at, UUID actorId) {
    return rules.trial(orderId, new ReviewRunInput(at, null), actorId);
  }

  // ================================================================ 批量审单

  /**
   * 批量通过 / 驳回 / 挂起。
   *
   * <p><b>幂等</b>：同一个幂等键重复提交 ⇒ 回放上一次的回执，<b>不再改任何状态</b>。
   * <p><b>加粗句</b>：通过必须指名规则（编号 + 版本），且逐单由那条规则真的判出来。
   */
  @Transactional
  public BatchReceipt decide(BatchDecisionInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    UUID tenant = currentTenant();
    OffsetDateTime at = OffsetDateTime.now();
    BatchAction action = input.action();
    if (action == null) {
      throw bad("批量动作不能为空（APPROVE 通过 / REJECT 驳回 / SUSPEND 挂起）");
    }
    List<UUID> orderIds = input.orderIds();
    if (orderIds == null || orderIds.isEmpty()) {
      throw bad("批量审单至少要有一张订单");
    }
    if (orderIds.size() > MAX_BATCH_SIZE) {
      throw bad("一次最多审 " + MAX_BATCH_SIZE + " 单，当前 " + orderIds.size() + " 单");
    }
    Set<UUID> distinct = new LinkedHashSet<>();
    for (UUID id : orderIds) {
      if (id == null) {
        throw bad("订单 id 不能为空");
      }
      if (!distinct.add(id)) {
        throw bad("同一批里出现了重复的订单 id：批量审单按「不同的订单」计数，"
            + "重复项会让回执的条数说不清是哪几单");
      }
    }

    String ruleCode = trim(input.ruleCode());
    Long ruleVersion = input.ruleVersion();
    String reason = trim(input.reason());
    String idempotencyKey = trim(input.idempotencyKey());
    if (idempotencyKey.isEmpty()) {
      throw bad("幂等键不能为空");
    }

    if (action == BatchAction.APPROVE) {
      // 🔴 加粗句的第一道闸：没有规则就没有「明确的规则」。
      if (ruleCode.isEmpty() || ruleVersion == null) {
        throw bad("批量通过必须指明依据的审单规则（ruleCode + ruleVersion）："
            + "「批量通过必须基于明确的规则，而不是默认全过」——"
            + "规则缺失时本模块**不提供**默认放行，要么补上规则，要么改用驳回/挂起");
      }
    } else if (reason.isEmpty()) {
      throw bad("批量" + action.label() + "必须写明理由："
          + "没有理由的结论，客服没法向买家解释");
    }

    // ---- 幂等：先占住键，再干活 ----
    UUID batchId = UUID.randomUUID();
    Record claimed =
        sql.fetchOne(
            "insert into order_review_batch(id,tenant_id,idempotency_key,action,rule_code,rule_version,"
                + " requested_count,reason,created_by_membership_id) "
                + "values(?::uuid,?,?,?,?,?,?,?,?::uuid) on conflict (tenant_id,idempotency_key) do nothing"
                + " returning id",
            batchId, tenant, idempotencyKey, action.name(), ruleCode, ruleVersion,
            orderIds.size(), reason, actorId);
    if (claimed == null) {
      return replay(tenant, idempotencyKey, action, ruleCode, ruleVersion, orderIds);
    }

    // ---- 规则校验（通过时）----
    if (action == BatchAction.APPROVE) {
      requireApprovableRule(tenant, ruleCode, ruleVersion, at);
    }

    // ---- 逐单处理 ----
    int approved = 0;
    int rejected = 0;
    int suspended = 0;
    int skipped = 0;
    List<BatchItemResult> items = new ArrayList<>();
    for (int i = 0; i < orderIds.size(); i++) {
      UUID orderId = orderIds.get(i);
      int seq = i + 1;
      Record order =
          sql.fetchOne(
              "select id, order_no, submitted_at, status from sales_order where tenant_id = ? and id = ?",
              tenant, orderId);
      if (order == null) {
        skipped++;
        items.add(item(seq, orderId, null, BatchOutcome.SKIPPED,
            "订单不存在或不属于当前租户（RLS 下查不到即视为不存在）"));
        continue;
      }
      String orderNo = order.get("order_no", String.class);
      if (!"SUBMITTED".equals(order.get("status", String.class))) {
        skipped++;
        items.add(item(seq, orderId, orderNo, BatchOutcome.SKIPPED,
            "订单不在审单队列里（单据状态是 " + order.get("status", String.class) + "，只有已提交的订单进队列）"));
        continue;
      }
      Record existing =
          sql.fetchOne(
              "select status from order_review_queue_case where tenant_id = ? and order_id = ?",
              tenant, orderId);
      if (existing != null) {
        String status = existing.get("status", String.class);
        if ("APPROVED".equals(status) || "REJECTED".equals(status)) {
          skipped++;
          items.add(item(seq, orderId, orderNo, BatchOutcome.SKIPPED,
              "该单已经审过了（" + labelOf(status) + "），终态不再重复处理"));
          continue;
        }
      }

      if (action == BatchAction.APPROVE) {
        // 逐单跑规则引擎：只有「判成 AUTO_APPROVE 且决定结论的规则就是指名的那条」才通过。
        ReviewDetail review = rules.review(orderId, new ReviewRunInput(at, null), actorId);
        ReviewDecision decision = review.decision();
        if (decision.decision() != ReviewDecisionType.AUTO_APPROVE) {
          skipped++;
          items.add(item(seq, orderId, orderNo, BatchOutcome.SKIPPED,
              "规则判定结果是「" + decision.decisionLabel() + "」：" + decision.decisionReason()));
          continue;
        }
        if (!ruleCode.equals(decision.winningRuleCode())) {
          skipped++;
          items.add(item(seq, orderId, orderNo, BatchOutcome.SKIPPED,
              "命中的规则是「" + nz(decision.winningRuleCode()) + "」，与本次指定的「" + ruleCode
                  + "」不是同一条：批量通过只能按指名的那条规则放行"));
          continue;
        }
        upsertCase(tenant, orderId, BatchOutcome.APPROVED, order.get("submitted_at", OffsetDateTime.class),
            ruleCode, ruleVersion, decision.id(), batchId, reason, actorId, at);
        approved++;
        items.add(item(seq, orderId, orderNo, BatchOutcome.APPROVED,
            "由规则「" + ruleCode + "」判定放行（命中链见该单的判定留痕）"));
      } else if (action == BatchAction.REJECT) {
        upsertCase(tenant, orderId, BatchOutcome.REJECTED, order.get("submitted_at", OffsetDateTime.class),
            "", null, null, batchId, reason, actorId, at);
        rejected++;
        items.add(item(seq, orderId, orderNo, BatchOutcome.REJECTED, reason));
      } else {
        upsertCase(tenant, orderId, BatchOutcome.SUSPENDED, order.get("submitted_at", OffsetDateTime.class),
            "", null, null, batchId, reason, actorId, at);
        suspended++;
        items.add(item(seq, orderId, orderNo, BatchOutcome.SUSPENDED, reason));
      }
    }

    // ---- 回写统计数（回执里唯一允许改写的部分）----
    sql.execute(
        "update order_review_batch set approved_count=?, rejected_count=?, suspended_count=?,"
            + " skipped_count=?, completed=true, completed_at=?::timestamptz where id=?::uuid"
            + " and tenant_id=?",
        approved, rejected, suspended, skipped, at, batchId, tenant);

    for (int i = 0; i < items.size(); i++) {
      BatchItemResult it = items.get(i);
      sql.execute(
          "insert into order_review_batch_item(id,tenant_id,batch_id,seq_no,order_id,outcome,reason)"
              + " values(?::uuid,?,?::uuid,?,?::uuid,?,?)",
          UUID.randomUUID(), tenant, batchId, it.seqNo(), it.orderId(), it.outcome().name(), it.reason());
    }

    return receipt(tenant, batchId, items, false,
        "本批实际通过 " + approved + " 单、驳回 " + rejected + " 单、挂起 " + suspended
            + " 单、跳过 " + skipped + " 单（共请求 " + orderIds.size() + " 单）。"
            + "逐条结果按请求顺序列出，跳过的都写清了原因。");
  }

  /** 幂等回放：同一个键再来一次 ⇒ 回放上一次的回执，不再改任何状态。 */
  private BatchReceipt replay(
      UUID tenant, String idempotencyKey, BatchAction action, String ruleCode, Long ruleVersion,
      List<UUID> orderIds) {
    Record b =
        sql.fetchOne(
            "select id,idempotency_key,action,rule_code,rule_version,requested_count,approved_count,"
                + " rejected_count,suspended_count,skipped_count,reason,completed,completed_at,"
                + " created_by_membership_id,created_at from order_review_batch"
                + " where tenant_id = ? and idempotency_key = ?",
            tenant, idempotencyKey);
    if (b == null) {
      throw conflict("幂等键已被占用但回执读不到，请稍后重试");
    }
    if (!b.get("completed", Boolean.class)) {
      throw conflict("同一个幂等键的批量审单正在处理中，请稍后重试");
    }
    // 同一个键必须对应同一份请求，否则「幂等」就变成了「悄悄换了内容」。
    if (!action.name().equals(b.get("action", String.class))
        || !Objects.equals(ruleCode, b.get("rule_code", String.class))
        || !Objects.equals(ruleVersion, b.get("rule_version", Long.class))
        || orderIds.size() != b.get("requested_count", Integer.class)) {
      throw conflict("这个幂等键已经被用在了另一次批量审单上（动作/规则/条数不同）。"
          + "幂等键必须对应唯一一份请求；换一批订单请换一个键");
    }
    UUID batchId = b.get("id", UUID.class);
    List<BatchItemResult> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select seq_no,order_id,outcome,reason from order_review_batch_item"
                + " where tenant_id = ? and batch_id = ? order by seq_no",
            tenant, batchId)) {
      items.add(
          new BatchItemResult(
              r.get("seq_no", Integer.class), r.get("order_id", UUID.class),
              null, BatchOutcome.valueOf(r.get("outcome", String.class)),
              BatchOutcome.valueOf(r.get("outcome", String.class)).label(),
              r.get("reason", String.class)));
    }
    // 条数一致但订单集合不同 → 同样是「另一次请求」。
    if (items.size() != orderIds.size()) {
      throw conflict("这个幂等键已经被用在了另一次批量审单上（条数不同）");
    }
    for (int i = 0; i < items.size(); i++) {
      if (!items.get(i).orderId().equals(orderIds.get(i))) {
        throw conflict("这个幂等键已经被用在了另一次批量审单上（订单不同）");
      }
    }
    return receipt(tenant, batchId, items, true,
        "这次是**幂等回放**：上一个用同一个幂等键的请求已经处理过，本模块没有再次改任何状态。"
            + "回执上的条数是上一次的实际结果。");
  }

  /** 批量回执列表（真分页 + 真搜索）。 */
  public BatchPage pageBatches(BatchQuery query) {
    UUID tenant = currentTenant();
    int size = pageSize(query == null ? 0 : query.size());
    int page = query == null || query.page() < 0 ? 0 : query.page();
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    if (query != null && notBlank(query.action())) {
      where.add("action = ?");
      binds.add(query.action().trim().toUpperCase(Locale.ROOT));
    }
    if (query != null && notBlank(query.keyword())) {
      where.add("(idempotency_key ilike '%' || ? || '%' or rule_code ilike '%' || ? || '%')");
      binds.add(query.keyword().trim());
      binds.add(query.keyword().trim());
    }
    String clause = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) c from order_review_batch" + clause, binds.toArray())
            .get("c", Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add(page * size);
    List<BatchRow> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,idempotency_key,action,rule_code,rule_version,requested_count,approved_count,"
                + " rejected_count,suspended_count,skipped_count,reason,completed,created_at"
                + " from order_review_batch" + clause + " order by created_at desc, id desc limit ? offset ?",
            pageBinds.toArray())) {
      BatchAction action = BatchAction.valueOf(r.get("action", String.class));
      items.add(
          new BatchRow(
              r.get("id", UUID.class), r.get("idempotency_key", String.class), action, action.label(),
              r.get("rule_code", String.class), r.get("rule_version", Long.class),
              r.get("requested_count", Integer.class), r.get("approved_count", Integer.class),
              r.get("rejected_count", Integer.class), r.get("suspended_count", Integer.class),
              r.get("skipped_count", Integer.class), nz(r.get("reason", String.class)),
              Boolean.TRUE.equals(r.get("completed", Boolean.class)),
              r.get("created_at", OffsetDateTime.class)));
    }
    return new BatchPage(items, total, page, size);
  }

  /** 批量回执详情（含逐条结果）。 */
  public BatchReceipt batch(UUID batchId) {
    UUID tenant = currentTenant();
    Record b =
        sql.fetchOne(
            "select id,idempotency_key,action,rule_code,rule_version,requested_count,approved_count,"
                + " rejected_count,suspended_count,skipped_count,reason,completed,completed_at,"
                + " created_by_membership_id,created_at from order_review_batch"
                + " where tenant_id = ? and id = ?",
            tenant, batchId);
    if (b == null) {
      throw notFound("批量审单回执不存在或不属于当前租户（RLS 下查不到即视为不存在）");
    }
    List<BatchItemResult> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select i.seq_no,i.order_id,i.outcome,i.reason,o.order_no"
                + " from order_review_batch_item i"
                + " left join sales_order o on o.tenant_id=i.tenant_id and o.id=i.order_id"
                + " where i.tenant_id = ? and i.batch_id = ? order by i.seq_no",
            tenant, batchId)) {
      BatchOutcome outcome = BatchOutcome.valueOf(r.get("outcome", String.class));
      items.add(
          new BatchItemResult(
              r.get("seq_no", Integer.class), r.get("order_id", UUID.class),
              r.get("order_no", String.class), outcome, outcome.label(), r.get("reason", String.class)));
    }
    return receipt(tenant, batchId, items, false,
        "回执是逐条留痕：每一条订单当时是什么结论、为什么，都写在回执里，改不掉。");
  }

  // ================================================================ 内部实现

  /**
   * 通过所依据的规则必须真的存在、此刻生效、动作是自动通过、且版本对得上。
   *
   * <p>任何一条不满足都**显式拒绝**（400），不给「就近放行」的兜底。
   */
  private void requireApprovableRule(UUID tenant, String ruleCode, long ruleVersion, OffsetDateTime at) {
    Record r =
        sql.fetchOne(
            "select id,rule_code,rule_name,action,forced_manual,status,effective_from,effective_to,version"
                + " from order_review_rule where tenant_id = ? and rule_code = ?",
            tenant, ruleCode);
    if (r == null) {
      throw bad("规则「" + ruleCode + "」不存在或不属于当前租户："
          + "批量通过必须基于**明确的规则**，指名的规则找不到时本模块不放行任何一单");
    }
    if (r.get("version", Long.class) != ruleVersion) {
      throw bad("规则「" + ruleCode + "」现在是第 " + r.get("version", Long.class) + " 版，"
          + "你指定的是第 " + ruleVersion + " 版：规则改版之后旧结论不可复算，请按当前版本重新发起");
    }
    if (!"ACTIVE".equals(r.get("status", String.class))) {
      throw bad("规则「" + ruleCode + "」当前不是启用状态（" + r.get("status", String.class) + "），"
          + "拿它当批量通过的依据等于没有依据");
    }
    OffsetDateTime from = r.get("effective_from", OffsetDateTime.class);
    OffsetDateTime to = r.get("effective_to", OffsetDateTime.class);
    if (from == null || from.isAfter(at) || (to != null && !to.isAfter(at))) {
      throw bad("规则「" + ruleCode + "」在本次审单时刻不处于生效期，不能作为批量通过的依据");
    }
    if (!"AUTO_APPROVE".equals(r.get("action", String.class))) {
      throw bad("规则「" + ruleCode + "」的动作是「" + r.get("action", String.class) + "」，"
          + "只有「自动通过」的规则才能作为批量通过的依据 —— "
          + "拿一条要求转人工的规则去批量放行，等于把「转人工」这层闸门拆了");
    }
    if (Boolean.TRUE.equals(r.get("forced_manual", Boolean.class))) {
      throw bad("规则「" + ruleCode + "」是强制转人工规则，不能作为批量通过的依据");
    }
  }

  /** 写/推进这一单的审单结论。一单一行，挂起 → 通过/驳回 就是改这一行。 */
  private void upsertCase(
      UUID tenant, UUID orderId, BatchOutcome outcome, OffsetDateTime enqueuedAt, String ruleCode,
      Long ruleVersion, UUID evaluationId, UUID batchId, String reason, UUID actorId,
      OffsetDateTime at) {
    String status = outcome.name();
    sql.execute(
        "insert into order_review_queue_case(id,tenant_id,order_id,status,enqueued_at,decided_at,"
            + " decided_by_membership_id,decision_reason,rule_code,rule_version,evaluation_id,batch_id,"
            + " version,created_at,updated_at) values(?::uuid,?,?::uuid,?,?::timestamptz,"
            + "?::timestamptz,?::uuid,?,?,?,?::uuid,?::uuid,0,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id,order_id) do update set status=excluded.status,"
            + " decided_at=excluded.decided_at, decided_by_membership_id=excluded.decided_by_membership_id,"
            + " decision_reason=excluded.decision_reason, rule_code=excluded.rule_code,"
            + " rule_version=excluded.rule_version, evaluation_id=excluded.evaluation_id,"
            + " batch_id=excluded.batch_id, version=order_review_queue_case.version+1,"
            + " updated_at=excluded.updated_at",
        UUID.randomUUID(), tenant, orderId, status, enqueuedAt, at, actorId, reason, ruleCode,
        ruleVersion, evaluationId, batchId, at, at);
  }

  /** 命中链：逐条件到订单字段与实际值。形状与 9.2.1.2 一致（RuleHit / HitCondition）。 */
  private List<RuleHit> hitChain(UUID tenant, UUID evaluationId) {
    List<Record> hits =
        sql.fetch(
            "select id,rule_id,rule_code,rule_name,priority,action,forced_manual,eval_rank,matched,reason"
                + " from order_review_rule_hit where tenant_id = ? and evaluation_id = ? order by eval_rank",
            tenant, evaluationId);
    if (hits.isEmpty()) {
      return List.of();
    }
    List<RuleHit> out = new ArrayList<>();
    for (Record h : hits) {
      UUID hitId = h.get("id", UUID.class);
      List<HitCondition> conditions = new ArrayList<>();
      for (Record c :
          sql.fetch(
              "select seq_no,dimension,operator,expected,actual_field,actual_value,satisfied,explanation"
                  + " from order_review_rule_hit_condition where tenant_id = ? and hit_id = ? order by seq_no",
              tenant, hitId)) {
        ConditionDimension dimension = ConditionDimension.valueOf(c.get("dimension", String.class));
        ConditionOperator operator = ConditionOperator.valueOf(c.get("operator", String.class));
        conditions.add(
            new HitCondition(
                c.get("seq_no", Integer.class), dimension, dimension.label(), operator,
                c.get("expected", String.class), c.get("actual_field", String.class),
                c.get("actual_value", String.class), Boolean.TRUE.equals(c.get("satisfied", Boolean.class)),
                c.get("explanation", String.class)));
      }
      ReviewAction action = ReviewAction.valueOf(h.get("action", String.class));
      out.add(
          new RuleHit(
              hitId, h.get("rule_id", UUID.class), h.get("rule_code", String.class),
              h.get("rule_name", String.class), h.get("priority", Integer.class), action, action.label(),
              Boolean.TRUE.equals(h.get("forced_manual", Boolean.class)), h.get("eval_rank", Integer.class),
              Boolean.TRUE.equals(h.get("matched", Boolean.class)), h.get("reason", String.class),
              conditions));
    }
    return out;
  }

  /** 风控五维明细（恒 5 行；缺信号的那行带着「为什么缺」）。 */
  private List<RiskComponent> riskComponents(UUID tenant, UUID assessmentId) {
    List<RiskComponent> out = new ArrayList<>();
    for (Record c :
        sql.fetch(
            "select id,dimension,status,weight,raw_value,normalized_score,contribution,signal_source,"
                + " signal_captured_at,explanation from order_risk_assessment_component"
                + " where tenant_id = ? and assessment_id = ? order by dimension",
            tenant, assessmentId)) {
      out.add(
          new RiskComponent(
              c.get("id", UUID.class),
              OrderRiskModels.RiskDimension.valueOf(c.get("dimension", String.class)),
              OrderRiskModels.RiskComponentStatus.valueOf(c.get("status", String.class)),
              c.get("weight", BigDecimal.class), c.get("raw_value", BigDecimal.class),
              c.get("normalized_score", BigDecimal.class), c.get("contribution", BigDecimal.class),
              OrderRiskModels.SignalSource.valueOf(c.get("signal_source", String.class)),
              c.get("signal_captured_at", OffsetDateTime.class), c.get("explanation", String.class)));
    }
    return out;
  }

  /** 这一单最近几次批量审单的结果。 */
  private List<PendingReviewBatchRef> recentBatches(UUID tenant, UUID orderId) {
    List<PendingReviewBatchRef> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select b.id,b.action,b.created_at,i.outcome,i.reason"
                + " from order_review_batch_item i"
                + " join order_review_batch b on b.tenant_id=i.tenant_id and b.id=i.batch_id"
                + " where i.tenant_id = ? and i.order_id = ? order by b.created_at desc, i.seq_no limit ?",
            tenant, orderId, MAX_RECENT_BATCHES)) {
      out.add(
          new PendingReviewBatchRef(
              r.get("id", UUID.class), r.get("action", String.class),
              r.get("created_at", OffsetDateTime.class), r.get("outcome", String.class),
              r.get("reason", String.class)));
    }
    return out;
  }

  // ================================================================ 行映射

  private PendingReviewRow row(Record r) {
    String queueStatus = r.get("queue_status", String.class);
    if (queueStatus == null) {
      queueStatus = "PENDING";
    }
    String timeliness = r.get("timeliness_state", String.class);
    TimelinessState state =
        timeliness == null ? TimelinessState.NO_DEADLINE : TimelinessState.valueOf(timeliness);
    return new PendingReviewRow(
        r.get("id", UUID.class), r.get("order_no", String.class),
        nz(r.get("external_order_no", String.class)), r.get("order_source", String.class),
        r.get("currency", String.class), r.get("grand_total_amount", BigDecimal.class),
        r.get("submitted_at", OffsetDateTime.class), r.get("waiting_hours", BigDecimal.class),
        r.get("timeliness_score", BigDecimal.class), state.name(), state.label(),
        queueStatus, labelOf(queueStatus), r.get("decided_at", OffsetDateTime.class),
        nz(r.get("decision_reason", String.class)), r.get("rule_code", String.class),
        r.get("rule_version", Long.class), r.get("batch_id", UUID.class),
        r.get("risk_score", BigDecimal.class), r.get("risk_recommended_action", String.class),
        r.get("risk_manual_review_required", Boolean.class),
        r.get("risk_evaluation_status", String.class), r.get("review_decision", String.class),
        r.get("review_mode", String.class), r.get("review_decision_reason", String.class),
        r.get("winning_rule_code", String.class), r.get("winning_rule_name", String.class),
        r.get("matched_rule_count", Integer.class) == null ? 0 : r.get("matched_rule_count", Integer.class),
        r.get("forced_rule_count", Integer.class) == null ? 0 : r.get("forced_rule_count", Integer.class),
        splitArray(r.get("hit_rule_codes", String[].class)),
        splitArray(r.get("address_anomaly_codes", String[].class)),
        r.get("recipient_name", String.class), r.get("country_code", String.class));
  }

  private ReviewDecision reviewDecision(Record e) {
    ReviewDecisionType type = ReviewDecisionType.valueOf(e.get("decision", String.class));
    ReviewMode mode = ReviewMode.valueOf(e.get("mode", String.class));
    return new ReviewDecision(
        e.get("id", UUID.class), e.get("order_id", UUID.class), e.get("order_no", String.class),
        e.get("order_source", String.class), e.get("currency_code", String.class),
        e.get("order_total", BigDecimal.class), e.get("discount_rate_percent", BigDecimal.class),
        mode, mode.label(), e.get("evaluated_scope", String.class), type, type.label(),
        e.get("decision_reason", String.class), e.get("winning_rule_id", UUID.class),
        nz(e.get("winning_rule_code", String.class)), nz(e.get("winning_rule_name", String.class)),
        e.get("considered_rule_count", Integer.class), e.get("matched_rule_count", Integer.class),
        e.get("forced_rule_count", Integer.class),
        splitArray(e.get("address_anomaly_codes", String[].class)),
        e.get("evaluated_at", OffsetDateTime.class), e.get("evaluated_by_membership_id", UUID.class),
        e.get("created_at", OffsetDateTime.class));
  }

  private BatchReceipt receipt(
      UUID tenant, UUID batchId, List<BatchItemResult> items, boolean replayed, String notes) {
    Record b =
        sql.fetchOne(
            "select id,idempotency_key,action,rule_code,rule_version,requested_count,approved_count,"
                + " rejected_count,suspended_count,skipped_count,reason,completed,completed_at,"
                + " created_by_membership_id,created_at from order_review_batch where tenant_id = ? and id = ?",
            tenant, batchId);
    if (b == null) {
      throw notFound("批量审单回执不存在或不属于当前租户");
    }
    BatchAction action = BatchAction.valueOf(b.get("action", String.class));
    return new BatchReceipt(
        b.get("id", UUID.class), b.get("idempotency_key", String.class), action, action.label(),
        b.get("rule_code", String.class), b.get("rule_version", Long.class),
        b.get("requested_count", Integer.class), b.get("approved_count", Integer.class),
        b.get("rejected_count", Integer.class), b.get("suspended_count", Integer.class),
        b.get("skipped_count", Integer.class), nz(b.get("reason", String.class)),
        Boolean.TRUE.equals(b.get("completed", Boolean.class)),
        b.get("completed_at", OffsetDateTime.class), b.get("created_by_membership_id", UUID.class),
        b.get("created_at", OffsetDateTime.class), items, replayed, notes);
  }

  private BatchItemResult item(
      int seq, UUID orderId, String orderNo, BatchOutcome outcome, String reason) {
    return new BatchItemResult(seq, orderId, orderNo, outcome, outcome.label(), reason);
  }

  // ================================================================ 小工具

  private static String labelOf(String status) {
    if (status == null) {
      return "";
    }
    return switch (status) {
      case "PENDING" -> QueueStatus.PENDING.label();
      case "SUSPENDED" -> QueueStatus.SUSPENDED.label();
      case "APPROVED" -> QueueStatus.APPROVED.label();
      case "REJECTED" -> QueueStatus.REJECTED.label();
      default -> status;
    };
  }

  /** 数组列读回来后按逗号切；数组内容本身不含逗号（词表受控）。 */
  private static List<String> splitArray(String[] values) {
    if (values == null || values.length == 0) {
      return List.of();
    }
    return List.of(values);
  }

  private static int pageSize(int requested) {
    return Math.min(MAX_PAGE_SIZE, Math.max(1, requested <= 0 ? 50 : requested));
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
