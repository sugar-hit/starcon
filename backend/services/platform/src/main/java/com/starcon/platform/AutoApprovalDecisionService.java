package com.starcon.platform;

import static com.starcon.platform.AutoApprovalModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AccessService.Access;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.3.3.1「自动审批」的**判定引擎**与**超时升级**这一侧。
 *
 * <p>🔴 <b>本模块拥有的是「决策」，不是「审批」</b>：上游 17.4（identity 服务）独家拥有
 * {@code approval_instance} 与 {@code approval_instance_action}。本类**不写**上游的表、
 * **不复制**实例、**不在平台侧另起一套审批状态机**。
 *
 * <p>🔴 <b>判定的形状（三条件缺一不可）</b>：
 *
 * <ol>
 *   <li><b>确定性违规 ⇒ 自动驳回</b>：只有「事实层面已经不合规」才驳回
 *       （金额与币种不成对、金额为负、金额越硬上限、命中登记过的 {@code VIOLATION} 信号）。
 *       「风险偏高」这类**主观判断**（{@code RISK} 信号）**绝不**驳回，只走人工。
 *   <li><b>小额 且 供应商已验证 且 无风险信号 ⇒ 自动通过</b>，缺一条就走人工。
 *   <li><b>其余一切 ⇒ 走人工</b>（并经授权端口真的提交上游，让上游的人工审批接手）。
 * </ol>
 *
 * <p>🔴 <b>信号的性质不由调用方自称</b>：只由**租户码表**决定；码表里查不到的码按
 * {@code RISK}（从严）处理 ⇒ 调用方编一个码就能把单子驳掉这件事不可能发生。
 *
 * <p>🔴 <b>金额比较一律 {@link BigDecimal#compareTo}</b>：{@code equals} 会把 {@code 100}
 * 与 {@code 100.00} 判成不等（scale 不同），而「金额是否小额」是本模块最要紧的判定。
 *
 * <p>🔴 <b>超时判定只有一个时钟基准</b>：用**上游算出来的停留秒数**
 * （identity 的 {@code approval_dwell_seconds}，DB 钟域，与升级规则的时限同一钟域）
 * 与 {@code timeout_hours} 比较；不用应用服务器墙钟去减一个上游时间戳（两个钟域相减，差值是错的），
 * 不用 {@code Timestamp.valueOf(LocalDateTime.now())}（会静默偏 −28800s），更不 sleep。
 * 本模块自己的落库时刻取**注入的 {@link Clock}**（可被测试钉死）。
 */
@Service
public class AutoApprovalDecisionService {

  /** 单次扫描最多看多少条待人工的单（真实上限，不是摆设）。 */
  private static final int MAX_SCAN = 100;

  private final DSLContext sql;
  private final ObjectMapper json;
  private final IdentityApprovalPort upstream;
  private final Clock clock;

  /**
   * 快照专用的 mapper：🔴 <b>{@code USE_BIG_DECIMAL_FOR_FLOATS}</b> 必须开 ——
   * Jackson 默认把 JSON 里的数字读成 {@code double}，于是 {@code 1000.000000} 会变成
   * {@code 1000.0}：金额的**数值**没丢，但**标度**丢了，而标度正是「这条依据当时长什么样」的一部分。
   */
  private static final ObjectMapper SNAPSHOT_JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  public AutoApprovalDecisionService(
      DSLContext sql, ObjectMapper json, IdentityApprovalPort upstream, Clock clock) {
    this.sql = sql;
    this.json = json;
    this.upstream = upstream;
    this.clock = clock;
  }

  // ================================================================ 判定

  /**
   * 一次自动审批判定：<b>判定 + 记录</b>。
   *
   * <p>⚠️ **提交上游失败也会留下决策**（{@code executionState=SUBMIT_FAILED}）：
   * 「这张单必须走人工」这个事实与「上游那次提交成功没有」是两件事，
   * 把后者混进前者会让台账说谎。
   */
  public AutoApprovalDecisionView evaluate(
      String bearer, Access actor, AutoApprovalEvaluateInput input) {
    var submitted = input.riskSignals() == null ? List.<AutoApprovalRiskSignalInput>of() : input.riskSignals();
    var signals = submitted.stream().map(this::signalSnapshot).toList();
    var checks = new ArrayList<AutoApprovalCheck>();

    // ---- 生效规则（一个单据类型 + 币种只有一条生效规则） ----
    var policy =
        input.currencyCode() == null ? null : activePolicy(input.documentTypeCode(), input.currencyCode());
    checks.add(
        new AutoApprovalCheck(
            "POLICY_ACTIVE",
            policy != null,
            policy == null
                ? "该单据类型 + 币种没有生效规则：缺规则时只能走人工"
                : "命中生效规则「" + policy.ruleName() + "」"));

    // ---- 确定性违规（只有这些才自动驳回） ----
    var paired = (input.amount() == null) == (input.currencyCode() == null);
    checks.add(
        new AutoApprovalCheck(
            "AMOUNT_CURRENCY_PAIRED",
            paired,
            paired
                ? "金额与币种成对（或都没有）"
                : "只有金额没有币种（或反之）：小额没有唯一答案"));

    var negative = input.amount() != null && input.amount().signum() < 0;
    checks.add(
        new AutoApprovalCheck(
            "AMOUNT_NOT_NEGATIVE",
            !negative,
            negative ? "金额为负" : "金额非负"));

    var hardLimit =
        policy == null || policy.amountHardLimit() == null || input.amount() == null
            ? null
            : policy.amountHardLimit();
    var aboveHard =
        hardLimit != null && input.amount().compareTo(hardLimit) > 0;
    checks.add(
        new AutoApprovalCheck(
            "AMOUNT_WITHIN_HARD_LIMIT",
            !aboveHard,
            hardLimit == null
                ? "本规则未设硬上限"
                : aboveHard
                    ? "金额 " + input.amount().toPlainString() + " 越过硬上限 " + hardLimit.toPlainString()
                    : "金额在硬上限 " + hardLimit.toPlainString() + " 之内"));

    var violations = signals.stream().filter(s -> "VIOLATION".equals(s.kind())).toList();
    checks.add(
        new AutoApprovalCheck(
            "NO_DETERMINISTIC_VIOLATION",
            violations.isEmpty(),
            violations.isEmpty()
                ? "没有命中确定性违规信号"
                : "命中确定性违规信号：" + violations.stream().map(AutoApprovalSignalSnapshot::code).toList()));

    // ---- 安全闸（三条件缺一不可） ----
    var small =
        policy != null
            && policy.autoApproveMaxAmount() != null
            && input.amount() != null
            && input.amount().compareTo(policy.autoApproveMaxAmount()) <= 0;
    checks.add(
        new AutoApprovalCheck(
            "SMALL_AMOUNT",
            small,
            policy == null
                ? "没有生效规则，无法判定是否小额"
                : policy.autoApproveMaxAmount() == null
                    ? "本规则未设小额上限：任何金额都不自动通过"
                    : small
                        ? "金额 " + input.amount().toPlainString() + " 在小额上限 "
                            + policy.autoApproveMaxAmount().toPlainString() + " 之内"
                        : "金额 " + input.amount().toPlainString() + " 超过小额上限 "
                            + policy.autoApproveMaxAmount().toPlainString()));

    var verified = Boolean.TRUE.equals(input.supplierVerified());
    checks.add(
        new AutoApprovalCheck(
            "SUPPLIER_VERIFIED",
            verified,
            verified ? "供应商已验证" : "供应商未验证：这一条不可配置，缺了就不自动通过"));

    var noSignal = signals.isEmpty();
    checks.add(
        new AutoApprovalCheck(
            "NO_RISK_SIGNAL",
            noSignal,
            noSignal
                ? "没有任何风险信号"
                : "有 " + signals.size() + " 条风险信号：一条都没有才谈得上「明确安全」"));

    // ---- 结论 ----
    String decision;
    String reasonCode;
    String reasonMessage;
    if (!paired) {
      decision = "AUTO_REJECTED";
      reasonCode = "AMOUNT_CURRENCY_UNPAIRED";
      reasonMessage = "金额与币种必须同时提供，只有一边时这张单无法被判定为小额";
    } else if (negative) {
      decision = "AUTO_REJECTED";
      reasonCode = "AMOUNT_NEGATIVE";
      reasonMessage = "金额为负：这是确定性不合规";
    } else if (aboveHard) {
      decision = "AUTO_REJECTED";
      reasonCode = "AMOUNT_ABOVE_HARD_LIMIT";
      reasonMessage = "金额越过本规则的上限（金额越界）：确定性不合规";
    } else if (!violations.isEmpty()) {
      decision = "AUTO_REJECTED";
      reasonCode = "DETERMINISTIC_VIOLATION";
      reasonMessage =
          "命中确定性违规信号："
              + violations.stream()
                  .map(s -> s.code() + (s.name() == null ? "" : "（" + s.name() + "）"))
                  .toList();
    } else if (small && verified && noSignal) {
      decision = "AUTO_APPROVED";
      reasonCode = "SAFE_LOW_AMOUNT_VERIFIED_SUPPLIER";
      reasonMessage = "小额 + 供应商已验证 + 无风险信号：属于明确安全的场景，自动通过";
    } else {
      decision = "MANUAL_REQUIRED";
      reasonCode = firstFailingCheck(checks);
      reasonMessage = manualReason(checks);
    }

    // ---- 执行：自动决策不产生待办；必须走人工的才真的提交上游 ----
    var executionState = "NOT_REQUIRED";
    UUID upstreamInstanceId = null;
    String upstreamInstanceNo = null;
    String upstreamNodeKey = null;
    String upstreamRoutedBy = null;
    AutoApprovalRoutingSnapshot routing;
    if ("MANUAL_REQUIRED".equals(decision)) {
      if (policy == null) {
        executionState = "SUBMIT_FAILED";
        routing =
            routingNote(
                null,
                input.currencyCode(),
                upstreamNodeKey,
                upstreamRoutedBy,
                "没有生效规则，未配置上游审批流：这张单还没能进入人工审批");
        reasonMessage = reasonMessage + "；提交上游失败：没有生效规则，未配置上游审批流";
      } else if (policy.upstreamFlowId() == null) {
        executionState = "SUBMIT_FAILED";
        routing =
            routingNote(
                null,
                input.currencyCode(),
                upstreamNodeKey,
                upstreamRoutedBy,
                "规则未配置上游审批流：这张单还没能进入人工审批");
        reasonMessage = reasonMessage + "；提交上游失败：规则未配置上游审批流";
      } else {
        try {
          var result =
              upstream.submit(
                  bearer,
                  policy.upstreamFlowId(),
                  input.sourceSystem().trim(),
                  input.documentRef().trim(),
                  subjectTitle(input),
                  input.currencyCode(),
                  input.amount());
          executionState = "SUBMITTED_TO_APPROVAL";
          upstreamInstanceId = result.id();
          upstreamInstanceNo = result.instanceNo();
          upstreamNodeKey = result.currentNodeKey();
          upstreamRoutedBy = result.routedBy();
          routing =
              routing(
                  bearer,
                  policy.upstreamFlowId(),
                  input.currencyCode(),
                  input.amount(),
                  result.currentNodeKey(),
                  result.routedBy());
        } catch (ResponseStatusException e) {
          executionState = "SUBMIT_FAILED";
          routing =
              routingNote(
                  policy.upstreamFlowId(),
                  input.currencyCode(),
                  null,
                  null,
                  "提交上游失败：" + (e.getReason() == null ? "上游不可达" : e.getReason()));
          reasonMessage = reasonMessage + "；提交上游失败：" + (e.getReason() == null ? "上游不可达" : e.getReason());
        }
      }
    } else {
      routing =
          routingNote(
              policy == null ? null : policy.upstreamFlowId(),
              input.currencyCode(),
              null,
              null,
              "自动决策不产生待办，也就没有审批人");
    }

    // ---- 落台账 ----
    var id = UUID.randomUUID();
    var decidedAt = OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
    var decisionNo =
        sql.fetchOne("select platform_next_auto_approval_decision_no() as no").get("no", String.class);
    sql.execute(
        "insert into platform_auto_approval_decision(id,tenant_id,decision_no,document_type_code,"
            + " document_ref,subject_title,source_system,currency_code,amount,supplier_id,"
            + " supplier_verified,decision,reason_code,reason_message,execution_state,"
            + " policy_snapshot,risk_signal_snapshot,routing_snapshot,checks,upstream_instance_id,"
            + " upstream_instance_no,upstream_node_key,upstream_routed_by,decided_at,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?::numeric,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,"
            + " ?::timestamptz,?,?::timestamptz)",
        id,
        tenant(),
        decisionNo,
        input.documentTypeCode().trim(),
        input.documentRef().trim(),
        subjectTitle(input),
        input.sourceSystem().trim(),
        input.currencyCode() == null ? "" : input.currencyCode(),
        input.amount(),
        input.supplierId(),
        verified,
        decision,
        reasonCode,
        reasonMessage,
        executionState,
        toJson(policySnapshot(policy)),
        toJson(signals),
        toJson(routing),
        toJson(checks),
        upstreamInstanceId,
        upstreamInstanceNo,
        upstreamNodeKey,
        upstreamRoutedBy,
        decidedAt,
        actor.membershipId(),
        decidedAt);
    return decision(id);
  }

  // ================================================================ 决策台账查询

  /** 决策台账列表（真筛选 + 真关键词 + 真分页上限）。 */
  public List<AutoApprovalDecisionRow> decisions(
      String documentTypeCode, String documentRef, String decision, String executionState,
      String keyword, int limit) {
    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();
    if (documentTypeCode != null && !documentTypeCode.isBlank()) {
      where.append(" and document_type_code = ?");
      args.add(documentTypeCode.trim());
    }
    if (documentRef != null && !documentRef.isBlank()) {
      where.append(" and document_ref = ?");
      args.add(documentRef.trim());
    }
    if (decision != null && !decision.isBlank()) {
      where.append(" and decision = ?");
      args.add(decision.trim());
    }
    if (executionState != null && !executionState.isBlank()) {
      where.append(" and execution_state = ?");
      args.add(executionState.trim());
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (document_ref ilike ? or subject_title ilike ? or reason_message ilike ?)");
      var like = "%" + keyword.trim() + "%";
      args.add(like);
      args.add(like);
      args.add(like);
    }
    args.add(Math.min(Math.max(limit, 1), 200));
    return sql.fetch(
            "select id,decision_no,document_type_code,document_ref,source_system,amount,currency_code,"
                + " decision,reason_code,execution_state,upstream_instance_id,decided_at"
                + " from platform_auto_approval_decision"
                + where
                + " order by decided_at desc, id desc limit ?",
            args.toArray())
        .map(
            r ->
                new AutoApprovalDecisionRow(
                    r.get("id", UUID.class),
                    r.get("decision_no", String.class),
                    r.get("document_type_code", String.class),
                    r.get("document_ref", String.class),
                    r.get("source_system", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("currency_code", String.class),
                    r.get("decision", String.class),
                    r.get("reason_code", String.class),
                    r.get("execution_state", String.class),
                    r.get("upstream_instance_id", UUID.class),
                    r.get("decided_at", OffsetDateTime.class)));
  }

  /** 一条决策详情（含**决策依据快照**与它的升级记录）。 */
  public AutoApprovalDecisionView decision(UUID id) {
    var row =
        sql.fetchOne(
            "select id,decision_no,document_type_code,document_ref,source_system,amount,currency_code,"
                + " supplier_id,supplier_verified,decision,reason_code,reason_message,execution_state,"
                + " policy_snapshot::text as policy_snapshot,"
                + " risk_signal_snapshot::text as risk_signal_snapshot,"
                + " routing_snapshot::text as routing_snapshot,"
                + " checks::text as checks,upstream_instance_id,upstream_instance_no,upstream_node_key,"
                + " upstream_routed_by,decided_at"
                + " from platform_auto_approval_decision where id = ?",
            id);
    if (row == null) throw error(HttpStatus.NOT_FOUND, "自动审批决策不存在或不属于当前租户");
    return new AutoApprovalDecisionView(
        row.get("id", UUID.class),
        row.get("decision_no", String.class),
        row.get("document_type_code", String.class),
        row.get("document_ref", String.class),
        row.get("source_system", String.class),
        row.get("amount", BigDecimal.class),
        row.get("currency_code", String.class),
        row.get("supplier_id", UUID.class),
        row.get("supplier_verified", Boolean.class) != null && row.get("supplier_verified", Boolean.class),
        row.get("decision", String.class),
        row.get("reason_code", String.class),
        row.get("reason_message", String.class),
        row.get("execution_state", String.class),
        fromJson(row.get("checks", String.class), new com.fasterxml.jackson.core.type.TypeReference<List<AutoApprovalCheck>>() {}),
        fromJson(
            row.get("policy_snapshot", String.class),
            new com.fasterxml.jackson.core.type.TypeReference<AutoApprovalPolicySnapshot>() {}),
        fromJson(
            row.get("risk_signal_snapshot", String.class),
            new com.fasterxml.jackson.core.type.TypeReference<List<AutoApprovalSignalSnapshot>>() {}),
        fromJson(
            row.get("routing_snapshot", String.class),
            new com.fasterxml.jackson.core.type.TypeReference<AutoApprovalRoutingSnapshot>() {}),
        row.get("upstream_instance_id", UUID.class),
        row.get("upstream_instance_no", String.class),
        row.get("upstream_node_key", String.class),
        row.get("upstream_routed_by", String.class),
        row.get("decided_at", OffsetDateTime.class),
        escalationsOfDecision(id));
  }

  // ================================================================ 超时升级

  /**
   * 扫描超时：找出「本模块判为必须人工、并已提交上游、且上游那条单已经压过时限」的单。
   *
   * <p>🔴 <b>这是上游明确没做的那件事</b>：上游的 {@code approval_flow_escalation} 只**定义**规则，
   * 它的注释逐字写着「本模块只定义它，不实现任何会去看它并放行的定时器」⇒
   * <b>看表并放行是本模块的活</b>。
   *
   * <p>🔴 **判定用的时钟是上游的 DB 钟**：上游算出来的停留秒数（{@code approval_dwell_seconds}）
   * 与规则里的 {@code timeout_hours} 比较 —— 两者同钟域，比较结果才有意义。
   */
  public AutoApprovalEscalationScanView scan(String bearer) {
    var pending =
        sql.fetch(
            "select id,upstream_instance_id,policy_snapshot::text as policy_snapshot"
                + " from platform_auto_approval_decision"
                + " where decision='MANUAL_REQUIRED' and execution_state='SUBMITTED_TO_APPROVAL'"
                + "   and upstream_instance_id is not null"
                + " order by decided_at, id limit ?",
            MAX_SCAN);
    var detected = new ArrayList<AutoApprovalEscalationRow>();
    for (var d : pending) {
      var instanceId = d.get("upstream_instance_id", UUID.class);
      var flowId = flowIdOf(d.get("policy_snapshot", String.class));
      if (flowId == null) continue;
      var record = upstream.record(bearer, instanceId);
      var view = record == null ? null : record.instance();
      if (view == null || !"PENDING".equals(view.status())) continue;
      var flow = upstream.flow(bearer, flowId);
      var rules =
          flow.escalations() == null
              ? List.<ApprovalEscalationView>of()
              : flow.escalations().stream()
                  .filter(r -> r.nodeKey() != null && r.nodeKey().equals(view.currentNodeKey()))
                  .toList();
      var dwell = view.dwellSeconds();
      var matched =
          rules.stream()
              .filter(r -> r.timeoutHours() > 0 && (long) r.timeoutHours() * 3600L <= dwell)
              .min(Comparator.comparingInt(ApprovalEscalationView::timeoutHours));
      if (matched.isEmpty()) continue;
      var rule = matched.get();
      var elapsed =
          BigDecimal.valueOf(dwell).divide(BigDecimal.valueOf(3600), 6, RoundingMode.HALF_UP);
      var id = UUID.randomUUID();
      var detectedAt = OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
      // 🔴 **用 ON CONFLICT DO NOTHING，而不是「插完再捕获唯一冲突」**：
      //    PostgreSQL 里一条失败语句会**毒化整个事务**（后续语句全部报 25P02），
      //    捕获异常再继续跑是跑不动的 —— 同一张单重复扫描会被这一条卡死。
      var inserted =
          sql.execute(
              "insert into platform_auto_approval_escalation(id,tenant_id,decision_id,"
                  + " upstream_instance_id,upstream_flow_id,node_key,timeout_hours,timeout_action,"
                  + " escalate_approver_type,escalate_role_id,escalate_position_id,escalate_membership_id,"
                  + " elapsed_hours,upstream_dwell_seconds,rule_snapshot,status,execution_detail,"
                  + " detected_at,created_at)"
                  + " values(?,?,?,?,?,?,?,?,?,?,?,?,?::numeric,?,?::jsonb,'DETECTED','',?::timestamptz,"
                  + " ?::timestamptz)"
                  + " on conflict (tenant_id,upstream_instance_id,node_key,timeout_hours) do nothing",
              id,
              tenant(),
              d.get("id", UUID.class),
              instanceId,
              flowId,
              view.currentNodeKey(),
              rule.timeoutHours(),
              rule.timeoutAction(),
              rule.escalateApproverType(),
              rule.escalateRoleId(),
              rule.escalatePositionId(),
              rule.escalateMembershipId(),
              elapsed,
              dwell,
              toJson(
                  java.util.Map.of(
                      "ruleId", String.valueOf(rule.id()),
                      "nodeKey", String.valueOf(rule.nodeKey()),
                      "timeoutAction", String.valueOf(rule.timeoutAction()),
                      "timeoutHours", rule.timeoutHours(),
                      "escalateApproverType", String.valueOf(rule.escalateApproverType()),
                      "escalateRoleId", String.valueOf(rule.escalateRoleId()),
                      "escalatePositionId", String.valueOf(rule.escalatePositionId()),
                      "escalateMembershipId", String.valueOf(rule.escalateMembershipId()),
                      "escalateLabel", String.valueOf(rule.escalateLabel()))),
              detectedAt,
              detectedAt);
      if (inserted > 0) detected.add(escalation(id));
    }
    return new AutoApprovalEscalationScanView(
        pending.size(),
        detected.size(),
        List.copyOf(detected),
        detected.isEmpty()
            ? "本次扫描没有发现超时未处理的单据"
            : "本次扫描发现 " + detected.size() + " 条超时单据，等待执行升级");
  }

  /** 超时升级台账列表。 */
  public List<AutoApprovalEscalationRow> escalations(String status, String nodeKey, int limit) {
    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      args.add(status.trim());
    }
    if (nodeKey != null && !nodeKey.isBlank()) {
      where.append(" and node_key = ?");
      args.add(nodeKey.trim());
    }
    args.add(Math.min(Math.max(limit, 1), 200));
    return sql.fetch(
            "select * from platform_auto_approval_escalation"
                + where
                + " order by detected_at desc, id desc limit ?",
            args.toArray())
        .map(this::escalation);
  }

  /** 一条升级台账。 */
  public AutoApprovalEscalationRow escalation(UUID id) {
    var row = sql.fetchOne("select * from platform_auto_approval_escalation where id = ?", id);
    if (row == null) throw error(HttpStatus.NOT_FOUND, "超时升级记录不存在或不属于当前租户");
    return escalation(row);
  }

  /**
   * 执行一次超时升级。
   *
   * <p>🔴 <b>以「当前审批人自己」的身份执行</b>：上游的审批动作只认**当前审批人自己的会话**
   * （{@code decide} / {@code transfer} 都是），所以这里转发的是**发起人的** bearer，
   * 本模块**不冒充**任何人。发起人不是当前审批人时，上游会如实拒绝 —— 那个拒绝会被原样记进
   * {@code executionDetail}，因为「谁有权放行」正是内控底线。
   *
   * <p>⚠️ 上游 5xx / 不可达**不**写成「执行失败」：那种是「再试一次」的事，
   * 写进台账会让一次临时抖动变成永久结论 —— 那种情况直接 503，升级记录保持 {@code DETECTED}。
   */
  public AutoApprovalEscalationRow executeEscalation(String bearer, Access actor, UUID id) {
    var row = escalation(id);
    if (!"DETECTED".equals(row.status()))
      throw error(HttpStatus.CONFLICT, "这条升级已经不是待执行状态（当前 " + row.status() + "）");
    var comment =
        "超时自动升级：节点 "
            + row.nodeKey()
            + " 已停留 "
            + row.elapsedHours().toPlainString()
            + " 小时（阈值 "
            + row.timeoutHours()
            + " 小时）";
    var outcome =
        "AUTO_APPROVE".equals(row.timeoutAction())
            ? upstream.decide(bearer, row.upstreamInstanceId(), "APPROVE", comment)
            : upstream.transfer(
                bearer, row.upstreamInstanceId(), resolveTarget(bearer, row), comment);
    sql.execute(
        "update platform_auto_approval_escalation set status=?, execution_detail=?,"
            + " executed_at=?::timestamptz, executed_by=? where id=?",
        outcome.ok() ? "EXECUTED" : "FAILED",
        outcome.message(),
        OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC),
        actor.membershipId(),
        id);
    return escalation(id);
  }

  // ================================================================ 内部：金额分层路由

  /**
   * 金额分层路由（**读上游的金额分支**，不另造区间语义）。
   *
   * <p>🔴 区间语义与上游 {@code ApprovalInboxController.startNode} **完全同口径**：
   * {@code [min_amount, max_amount)} 左闭右开；多条命中时按 {@code priority desc, id} 取第一条。
   */
  private AutoApprovalRoutingSnapshot routing(
      String bearer,
      UUID flowId,
      String currencyCode,
      BigDecimal amount,
      String upstreamNodeKey,
      String upstreamRoutedBy) {
    try {
      var flow = upstream.flow(bearer, flowId);
      var branches = flow.branches() == null ? List.<ApprovalBranchView>of() : flow.branches();
      var matched =
          branches.stream()
              .filter(b -> currencyCode == null || currencyCode.equals(b.currencyCode()))
              .filter(
                  b ->
                      amount == null
                          || ((b.minAmount() == null || amount.compareTo(b.minAmount()) >= 0)
                              && (b.maxAmount() == null || amount.compareTo(b.maxAmount()) < 0)))
              .sorted(
                  Comparator.comparingInt(ApprovalBranchView::priority)
                      .reversed()
                      .thenComparing(ApprovalBranchView::id))
              .findFirst();
      if (matched.isEmpty()) {
        return new AutoApprovalRoutingSnapshot(
            false,
            flowId,
            flow.flow() == null ? null : flow.flow().flowName(),
            currencyCode,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            upstreamNodeKey,
            upstreamRoutedBy,
            "该金额没有落进上游任何一条金额分支，走流程的默认串行路径");
      }
      var branch = matched.get();
      var node =
          flow.nodes() == null
              ? null
              : flow.nodes().stream()
                  .filter(n -> branch.targetNodeKey() != null && branch.targetNodeKey().equals(n.nodeKey()))
                  .findFirst()
                  .orElse(null);
      return new AutoApprovalRoutingSnapshot(
          true,
          flowId,
          flow.flow() == null ? null : flow.flow().flowName(),
          currencyCode,
          branch.branchName(),
          branch.minAmount(),
          branch.maxAmount(),
          branch.targetNodeKey(),
          node == null ? null : node.nodeName(),
          node == null ? null : node.approverType(),
          node == null ? null : node.roleId(),
          node == null ? null : node.positionId(),
          node == null ? null : node.membershipId(),
          upstreamNodeKey,
          upstreamRoutedBy,
          "金额落在上游分支「" + branch.branchName() + "」");
    } catch (ResponseStatusException e) {
      return routingNote(
          flowId, currencyCode, upstreamNodeKey, upstreamRoutedBy,
          "上游流程详情读不到，金额分层未能在本次判定里复核（上游已按自己的规则完成路由）");
    }
  }

  /** 走人工但没能算出金额分层时的快照（**如实写明为什么**）。 */
  private AutoApprovalRoutingSnapshot routingNote(
      UUID flowId, String currencyCode, String upstreamNodeKey, String upstreamRoutedBy, String note) {
    return new AutoApprovalRoutingSnapshot(
        false, flowId, null, currencyCode, null, null, null, null, null, null, null, null, null,
        upstreamNodeKey, upstreamRoutedBy, note);
  }

  /**
   * 把升级目标解析成**具体成员**（上游的转交通道只接受具体成员）。
   *
   * <p>🔴 只在**唯一解**时才解析：角色下有多名在职成员时，本模块**不替租户挑一个人**
   * （挑谁是审批策略，不是本模块该做的决定）⇒ 如实拒绝并说明该由谁来做。
   */
  private UUID resolveTarget(String bearer, AutoApprovalEscalationRow row) {
    var type = row.escalateApproverType();
    if ("USER".equals(type)) return row.escalateMembershipId();
    if ("ROLE".equals(type)) {
      var members = upstream.members(bearer);
      var candidates =
          members.stream()
              .filter(m -> "ACTIVE".equals(m.status()))
              .filter(
                  m ->
                      m.assignments() != null
                          && m.assignments().stream()
                              .anyMatch(a -> row.escalateRoleId().equals(a.roleId())))
              .toList();
      if (candidates.size() == 1) return candidates.get(0).id();
      if (candidates.isEmpty())
        throw error(HttpStatus.CONFLICT, "升级目标角色下没有在职成员：这次升级没法转交");
      throw error(
          HttpStatus.CONFLICT,
          "升级目标角色下有 "
              + candidates.size()
              + " 名在职成员，而上游转交通道只接受具体成员：请由当前审批人在待办里转交给指定的人");
    }
    throw error(
        HttpStatus.CONFLICT,
        "升级目标是岗位：上游的岗位字典不携带成员绑定，解析不到具体成员，这次升级没法转交");
  }

  // ================================================================ 内部工具

  /** 生效规则（一个单据类型 + 币种只有一条生效规则）。 */
  private AutoApprovalPolicyRow activePolicy(String documentTypeCode, String currencyCode) {
    var row =
        sql.fetchOne(
            "select * from platform_auto_approval_policy"
                + " where document_type_code = ? and currency_code = ? and status='ACTIVE'",
            documentTypeCode.trim(),
            currencyCode.trim());
    return row == null ? null : new AutoApprovalPolicyRow(
        row.get("id", UUID.class),
        row.get("rule_code", String.class),
        row.get("rule_name", String.class),
        row.get("document_type_code", String.class),
        row.get("currency_code", String.class),
        row.get("auto_approve_max_amount", BigDecimal.class),
        row.get("amount_hard_limit", BigDecimal.class),
        row.get("upstream_flow_id", UUID.class),
        row.get("status", String.class),
        row.get("priority", Integer.class) == null ? 0 : row.get("priority", Integer.class),
        row.get("remark", String.class),
        row.get("created_by", UUID.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 信号的**性质**由租户码表决定；码表里查不到 ⇒ 按 RISK（从严）处理。 */
  private AutoApprovalSignalSnapshot signalSnapshot(AutoApprovalRiskSignalInput input) {
    var row =
        sql.fetchOne(
            "select signal_code,signal_name,signal_kind from platform_auto_approval_signal"
                + " where signal_code = ? and status='ACTIVE'",
            input.code().trim());
    if (row == null)
      return new AutoApprovalSignalSnapshot(
          input.code().trim(), null, "RISK", false, input.detail() == null ? "" : input.detail());
    return new AutoApprovalSignalSnapshot(
        row.get("signal_code", String.class),
        row.get("signal_name", String.class),
        row.get("signal_kind", String.class),
        true,
        input.detail() == null ? "" : input.detail());
  }

  /** 判定当时的规则快照（**不是**事后读当下的规则）。 */
  private AutoApprovalPolicySnapshot policySnapshot(AutoApprovalPolicyRow policy) {
    if (policy == null)
      return new AutoApprovalPolicySnapshot(
          false, null, null, null, null, null, null, null, null, null, null);
    return new AutoApprovalPolicySnapshot(
        true,
        policy.id(),
        policy.ruleCode(),
        policy.ruleName(),
        policy.documentTypeCode(),
        policy.currencyCode(),
        policy.autoApproveMaxAmount(),
        policy.amountHardLimit(),
        policy.upstreamFlowId(),
        policy.status(),
        policy.updatedAt());
  }

  private List<AutoApprovalEscalationRow> escalationsOfDecision(UUID decisionId) {
    return sql.fetch(
            "select * from platform_auto_approval_escalation where decision_id = ? order by detected_at, id",
            decisionId)
        .map(this::escalation);
  }

  private AutoApprovalEscalationRow escalation(Record row) {
    return new AutoApprovalEscalationRow(
        row.get("id", UUID.class),
        row.get("decision_id", UUID.class),
        row.get("upstream_instance_id", UUID.class),
        row.get("upstream_flow_id", UUID.class),
        row.get("node_key", String.class),
        row.get("timeout_hours", Integer.class) == null ? 0 : row.get("timeout_hours", Integer.class),
        row.get("timeout_action", String.class),
        row.get("escalate_approver_type", String.class),
        row.get("escalate_role_id", UUID.class),
        row.get("escalate_position_id", UUID.class),
        row.get("escalate_membership_id", UUID.class),
        row.get("elapsed_hours", BigDecimal.class),
        row.get("upstream_dwell_seconds", Long.class) == null
            ? 0L
            : row.get("upstream_dwell_seconds", Long.class),
        row.get("status", String.class),
        row.get("execution_detail", String.class),
        row.get("detected_at", OffsetDateTime.class),
        row.get("executed_at", OffsetDateTime.class),
        row.get("executed_by", UUID.class));
  }

  private UUID flowIdOf(String policySnapshotJson) {
    if (policySnapshotJson == null) return null;
    try {
      var node = json.readTree(policySnapshotJson).get("upstreamFlowId");
      if (node == null || node.isNull()) return null;
      return UUID.fromString(node.asText());
    } catch (Exception e) {
      return null;
    }
  }

  private static String subjectTitle(AutoApprovalEvaluateInput input) {
    if (input.subjectTitle() != null && !input.subjectTitle().isBlank())
      return input.subjectTitle().trim();
    return input.documentRef().trim();
  }

  /** 走人工的原因：取第一条不成立的判定。 */
  private static String firstFailingCheck(List<AutoApprovalCheck> checks) {
    for (var c : checks) if (!c.passed()) return c.code();
    return "MANUAL_REQUIRED";
  }

  private static String manualReason(List<AutoApprovalCheck> checks) {
    var sb = new StringBuilder();
    for (var c : checks) {
      if (c.passed()) continue;
      if (sb.length() > 0) sb.append("；");
      sb.append(c.code()).append("：").append(c.detail());
    }
    return sb.length() == 0 ? "不满足自动通过的全部条件，走人工审批" : sb.toString();
  }

  private UUID tenant() {
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    if (raw == null || raw.isBlank()) throw error(HttpStatus.FORBIDDEN, "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  private String toJson(Object value) {
    try {
      return SNAPSHOT_JSON.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T fromJson(String text, com.fasterxml.jackson.core.type.TypeReference<T> type) {
    if (text == null || text.isBlank()) return null;
    try {
      return SNAPSHOT_JSON.readValue(text, type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ResponseStatusException error(HttpStatus status, String message) {
    return new ResponseStatusException(status, message);
  }
}
