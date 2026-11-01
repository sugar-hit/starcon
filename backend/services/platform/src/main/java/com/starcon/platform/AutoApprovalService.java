package com.starcon.platform;

import static com.starcon.platform.AutoApprovalModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AccessService.Access;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.3.3.1「自动审批」的**规则配置与码表**这一侧。
 *
 * <p>本类只管「配置」：自动审批规则、风险信号码表、能力声明、概览。
 * 「判定」与「超时升级」在 {@link AutoApprovalDecisionService}。
 *
 * <p>🔴 <b>租户来自已认证成员关系</b>：本类所有 SQL 都跑在 {@code AccessService.run}
 * 绑定的事务里，{@code app.current_tenant} 由 RLS 强制；{@code X-Tenant-Id} 头从头到尾
 * 没有出现过，请求体里也没有任何一处接受调用方指定的租户。
 *
 * <p>🔴 <b>金额一律 BigDecimal + NUMERIC</b>：小额上限与硬上限都存在 {@code numeric(19,6)} 列里，
 * 比较一律 {@link BigDecimal#compareTo} —— {@code equals} 会把 {@code 100} 与 {@code 100.00}
 * 判成不等（scale 不同），而「金额是否小额」正是本模块最要紧的一个判定。
 */
@Service
public class AutoApprovalService {

  private final DSLContext sql;
  private final ObjectMapper json;

  public AutoApprovalService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：这条模块**能**做什么、**不能**做什么、边界归谁。
   *
   * <p>🔴 {@code upstreamProgrammaticDecisionChannel} 恒为 {@code false}，而且**必须**如实写出来：
   * 上游 17.4 的 {@code decide} / {@code transfer} 都绑定**当前用户自己的会话**
   * （actor 恒为调用者、取单条件是「这条待办归我」），**没有**可供服务间调用的程序化决策通道。
   * 页面与脚本据此知道：自动审批能「判定并记录」，但**不能**替人改上游实例的状态。
   */
  public AutoApprovalCapabilities capabilities() {
    return new AutoApprovalCapabilities(
        "18.3.3.1 自动审批（platform 服务）",
        "17.4 审批（identity 服务）",
        false,
        "上游没有程序化通过/驳回通道：decide 与 transfer 都绑定当前审批人自己的会话，"
            + "actor 恒为调用者、取单条件是「这条待办归我」。"
            + "本模块因此是「判定 + 记录 + 经授权端口转交」，不在平台侧另起一套审批状态机。",
        "有：POST /identity/approval-instances（X-Internal-Key + 已认证会话）"
            + "—— 必须走人工的单据经这条通道真的进入上游人工审批。",
        "自动审批决策（结论 + 决策依据快照）归本模块；"
            + "审批实例与审批动作归上游 17.4，本模块不复制、不改写。",
        List.of("AUTO_APPROVED", "AUTO_REJECTED", "MANUAL_REQUIRED"),
        List.of(
            "POLICY_ACTIVE",
            "AMOUNT_CURRENCY_PAIRED",
            "AMOUNT_WITHIN_HARD_LIMIT",
            "NO_DETERMINISTIC_VIOLATION",
            "SMALL_AMOUNT",
            "SUPPLIER_VERIFIED",
            "NO_RISK_SIGNAL"),
        List.of(
            "platform.auto_approval.read",
            "platform.auto_approval.create",
            "platform.auto_approval.update",
            "platform.auto_approval.decide"));
  }

  /** 概览：几个数（真数，不是占位）。 */
  public AutoApprovalOverview overview() {
    var policyCount = count("select count(*) from platform_auto_approval_policy");
    var activePolicyCount =
        count("select count(*) from platform_auto_approval_policy where status='ACTIVE'");
    var signalCount = count("select count(*) from platform_auto_approval_signal");
    var decisionCount = count("select count(*) from platform_auto_approval_decision");
    var approved =
        count("select count(*) from platform_auto_approval_decision where decision='AUTO_APPROVED'");
    var rejected =
        count("select count(*) from platform_auto_approval_decision where decision='AUTO_REJECTED'");
    var manual =
        count("select count(*) from platform_auto_approval_decision where decision='MANUAL_REQUIRED'");
    var pendingEscalation =
        count("select count(*) from platform_auto_approval_escalation where status='DETECTED'");
    var executedEscalation =
        count("select count(*) from platform_auto_approval_escalation where status='EXECUTED'");
    return new AutoApprovalOverview(
        policyCount,
        activePolicyCount,
        signalCount,
        decisionCount,
        approved,
        rejected,
        manual,
        pendingEscalation,
        executedEscalation);
  }

  // ================================================================ 规则配置

  /** 规则配置列表（真筛选 + 真关键词）。 */
  public List<AutoApprovalPolicyRow> policies(
      String documentTypeCode, String currencyCode, String status, String keyword) {
    var where = new StringBuilder(" where 1=1");
    var args = new java.util.ArrayList<Object>();
    if (documentTypeCode != null && !documentTypeCode.isBlank()) {
      where.append(" and document_type_code = ?");
      args.add(documentTypeCode.trim());
    }
    if (currencyCode != null && !currencyCode.isBlank()) {
      where.append(" and currency_code = ?");
      args.add(currencyCode.trim());
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      args.add(status.trim());
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (rule_code ilike ? or rule_name ilike ?)");
      var like = "%" + keyword.trim() + "%";
      args.add(like);
      args.add(like);
    }
    return sql.fetch(
            "select * from platform_auto_approval_policy" + where + " order by created_at desc, id",
            args.toArray())
        .map(this::policy);
  }

  /** 一条规则配置。 */
  public AutoApprovalPolicyRow policy(UUID id) {
    var row = sql.fetchOne("select * from platform_auto_approval_policy where id = ?", id);
    if (row == null) throw error(HttpStatus.NOT_FOUND, "自动审批规则不存在或不属于当前租户");
    return policy(row);
  }

  /**
   * 登记一条规则配置。
   *
   * <p>🔴 **一个单据类型 + 币种只能有一条生效规则**（DB 层部分唯一索引）：
   * 两条生效规则同时命中时「哪条说了算」没有答案，而自动审批的方向一旦选错，
   * 代价是真金白银。
   */
  public AutoApprovalPolicyRow createPolicy(Access actor, AutoApprovalPolicyInput input) {
    validatePolicy(input);
    var id = UUID.randomUUID();
    var now = dbNow();
    try {
      sql.execute(
          "insert into platform_auto_approval_policy(id,tenant_id,rule_code,rule_name,"
              + " document_type_code,currency_code,auto_approve_max_amount,amount_hard_limit,"
              + " upstream_flow_id,status,priority,remark,created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?::numeric,?::numeric,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          tenant(),
          input.ruleCode().trim(),
          input.ruleName().trim(),
          input.documentTypeCode().trim(),
          input.currencyCode().trim(),
          input.autoApproveMaxAmount(),
          input.amountHardLimit(),
          input.upstreamFlowId(),
          input.status() == null ? "ACTIVE" : input.status(),
          input.priority() == null ? 0 : input.priority(),
          input.remark() == null ? "" : input.remark().trim(),
          actor.membershipId(),
          now,
          now);
    } catch (org.jooq.exception.DataAccessException e) {
      throw conflict(e, "这条规则登记不了：同一单据类型 + 币种已有一条生效规则，或规则编码已被占用");
    }
    return policy(id);
  }

  /** 改一条规则配置。 */
  public AutoApprovalPolicyRow updatePolicy(Access actor, UUID id, AutoApprovalPolicyInput input) {
    policy(id);
    validatePolicy(input);
    try {
      sql.execute(
          "update platform_auto_approval_policy set rule_name=?,document_type_code=?,currency_code=?,"
              + " auto_approve_max_amount=?::numeric,amount_hard_limit=?::numeric,upstream_flow_id=?,"
              + " status=?,priority=?,remark=?,updated_at=?::timestamptz where id=?",
          input.ruleName().trim(),
          input.documentTypeCode().trim(),
          input.currencyCode().trim(),
          input.autoApproveMaxAmount(),
          input.amountHardLimit(),
          input.upstreamFlowId(),
          input.status() == null ? "ACTIVE" : input.status(),
          input.priority() == null ? 0 : input.priority(),
          input.remark() == null ? "" : input.remark().trim(),
          dbNow(),
          id);
    } catch (org.jooq.exception.DataAccessException e) {
      throw conflict(e, "这条规则改不了：同一单据类型 + 币种已有一条生效规则");
    }
    return policy(id);
  }

  /**
   * 停用一条规则配置（**不删**）。
   *
   * <p>🔴 历史决策要能复现：规则被删掉之后，过去的「依据快照」就再也拼不出当时的规则了。
   */
  public AutoApprovalPolicyRow disablePolicy(UUID id) {
    policy(id);
    sql.execute(
        "update platform_auto_approval_policy set status='DISABLED', updated_at=?::timestamptz where id=?",
        dbNow(),
        id);
    return policy(id);
  }

  // ================================================================ 风险信号码表

  /** 风险信号码表列表（真筛选 + 真关键词）。 */
  public List<AutoApprovalSignalRow> signals(String signalKind, String status, String keyword) {
    var where = new StringBuilder(" where 1=1");
    var args = new java.util.ArrayList<Object>();
    if (signalKind != null && !signalKind.isBlank()) {
      where.append(" and signal_kind = ?");
      args.add(signalKind.trim());
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      args.add(status.trim());
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (signal_code ilike ? or signal_name ilike ?)");
      var like = "%" + keyword.trim() + "%";
      args.add(like);
      args.add(like);
    }
    return sql.fetch(
            "select * from platform_auto_approval_signal" + where + " order by created_at desc, id",
            args.toArray())
        .map(this::signal);
  }

  /** 登记一个风险信号码。 */
  public AutoApprovalSignalRow createSignal(Access actor, AutoApprovalSignalInput input) {
    try {
      var id = UUID.randomUUID();
      var now = dbNow();
      sql.execute(
          "insert into platform_auto_approval_signal(id,tenant_id,signal_code,signal_name,"
              + " signal_kind,description,status,created_by,created_at,updated_at)"
              + " values(?,?,?,?,?,?,'ACTIVE',?,?::timestamptz,?::timestamptz)",
          id,
          tenant(),
          input.signalCode().trim(),
          input.signalName().trim(),
          input.signalKind(),
          input.description() == null ? "" : input.description().trim(),
          actor.membershipId(),
          now,
          now);
      return signal(id);
    } catch (org.jooq.exception.DataAccessException e) {
      throw conflict(e, "这个信号码登记不了：该编码已被占用");
    }
  }

  /** 停用一个风险信号码（**不删**：历史决策的快照里还引用着它）。 */
  public AutoApprovalSignalRow disableSignal(UUID id) {
    var row = sql.fetchOne("select * from platform_auto_approval_signal where id = ?", id);
    if (row == null) throw error(HttpStatus.NOT_FOUND, "风险信号不存在或不属于当前租户");
    sql.execute(
        "update platform_auto_approval_signal set status='DISABLED', updated_at=?::timestamptz where id=?",
        dbNow(),
        id);
    return signal(id);
  }

  // ================================================================ 内部工具

  /** 事务局部 GUC：租户由 AccessService 绑定，这里读的是**同一个**绑定。 */
  private UUID tenant() {
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  /** DB 时钟：读一次 {@code clock_timestamp()} 再绑定。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class);
  }

  private long count(String sqlText, Object... args) {
    return sql.fetchOne(sqlText, args).get(0, Long.class);
  }

  private AutoApprovalPolicyRow policy(Record row) {
    return new AutoApprovalPolicyRow(
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

  private AutoApprovalSignalRow signal(Record row) {
    return new AutoApprovalSignalRow(
        row.get("id", UUID.class),
        row.get("signal_code", String.class),
        row.get("signal_name", String.class),
        row.get("signal_kind", String.class),
        row.get("description", String.class),
        row.get("status", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  /** 一个风险信号码（按 id 取）。 */
  public AutoApprovalSignalRow signal(UUID id) {
    var row = sql.fetchOne("select * from platform_auto_approval_signal where id = ?", id);
    if (row == null) throw error(HttpStatus.NOT_FOUND, "风险信号不存在或不属于当前租户");
    return signal(row);
  }

  private void validatePolicy(AutoApprovalPolicyInput input) {
    check(
        input.autoApproveMaxAmount() == null || input.autoApproveMaxAmount().signum() >= 0,
        "小额上限不能为负");
    check(
        input.amountHardLimit() == null || input.amountHardLimit().signum() >= 0, "硬上限不能为负");
    check(
        input.autoApproveMaxAmount() == null
            || input.amountHardLimit() == null
            || input.autoApproveMaxAmount().compareTo(input.amountHardLimit()) <= 0,
        "小额上限不能大于硬上限：否则同一笔金额会同时「够小」又「越界」，判定自相矛盾");
  }

  private static void check(boolean value, String message) {
    if (!value) throw error(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(
      org.jooq.exception.DataAccessException cause, String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message, cause);
  }

  private static ResponseStatusException error(HttpStatus status, String message) {
    return new ResponseStatusException(status, message);
  }
}
