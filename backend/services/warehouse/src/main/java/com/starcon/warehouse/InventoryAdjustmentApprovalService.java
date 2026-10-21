package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryAdjustmentApprovalModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.4.1.2 调整审批。
 *
 * <p>蓝图：「**按金额/数量阈值审批调整单，审批通过后才执行库存分录；支持批量审批与驳回。**」
 * 输入：「待审调整单；17 审批规则。」输出：「审批结果；执行后的库存分录；审计记录。」
 * 以及：「**库存调整是舞弊高发区**（无审批的调整 = 任意改账），阈值审批是必要控制。」
 *
 * <h2>与 7.4.1.1 的边界（上游会话日志已写死，本模块照抄）</h2>
 *
 * <ul>
 *   <li><b>阈值审批规则</b>（金额/数量阈值、批量审批、驳回工作流）⇒ <b>本模块</b>；
 *   <li><b>执行「批准」这一动作</b> ⇒ <b>7.4.1.1</b>。本模块**不重复实现**它，
 *       只在它前面接一道阈值判定：判定通过 ⇒ 调 {@link InventoryAdjustmentService#approve}。
 * </ul>
 *
 * <h2>为什么自持规则表（不委托 17 组织的通用审批规则服务）</h2>
 *
 * identity 的 {@code approval_trigger_rule}（V89）维度只有「金额 + 跨法人/跨部门/新供应商/
 * 高风险商品」，<b>没有「数量阈值」，也没有「调整单类型」</b>；它的 evaluate 入参同样传不进去。
 * 而蓝图把两者都点明了。6.3.1.3 采购单审批（V262）踩过同一个坑并已证明结论 ⇒ 同口径自持，
 * 能力声明里 {@code identityApproval} 恒 {@code available=false} 并点名原因。
 *
 * <h2>金额为什么是「申报值」</h2>
 *
 * warehouse 库里没有任何成本口径（{@code inventory_balance} / {@code stock_bucket} 都没有金额列），
 * 12 财务服务也不存在 ⇒ 调整单没有原生金额。本模块的金额是提交人<b>申报</b>、
 * <b>提交那一刻冻结</b>、进审批链路的登记额；真正的估值口径随 12 财务落地后衔接。
 */
@Service
public class InventoryAdjustmentApprovalService {

  private static final BigDecimal ZERO = new BigDecimal("0.0000");
  private static final int AMOUNT_SCALE = 4;
  private static final DateTimeFormatter CASE_DAY =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
  private static final String SAVEPOINT = "adjustment_approval_item";

  /** 调整单类型（= 7.4.1.1 的受控原因码）白名单；{@code ANY} 是通配。 */
  private static final List<String> REASON_CODES =
      List.of(
          "ANY",
          "OVERAGE",
          "SHORTAGE",
          "DAMAGE",
          "SCRAP",
          "MISSHIP_CORRECTION",
          "PLATFORM_VARIANCE");

  private static final List<String> TIERS = List.of("L1", "L2", "L3");

  /**
   * 审批单的**统一投影**：所有喂给 {@link #caseView} 的查询都用这一串列。
   *
   * <p>为什么不用 {@code select *}：jOOQ 的 {@code record.get(name, type)} 遇到
   * **不存在的列会直接抛 IllegalArgumentException**，而列名是否一致取决于每处查询自己写 ——
   * 少写一列就是运行期才炸。所以列集合固定在这里，三处查询共用。
   */
  private static final String CASE_COLUMNS =
      "c.id as id, c.order_id as order_id, c.case_no as case_no, c.attempt_no as attempt_no,"
          + "c.rule_id as rule_id, c.rule_name as rule_name, c.rule_match as rule_match,"
          + "c.reason_code as reason_code, c.order_amount as order_amount, c.currency as currency,"
          + "c.order_qty as order_qty, c.required_tier as required_tier,"
          + "c.required_approvals as required_approvals, c.approved_count as approved_count,"
          + "c.status as status, c.submitted_by as submitted_by, c.submitted_at as submitted_at,"
          + "c.rejected_by as rejected_by, c.rejected_at as rejected_at,"
          + "c.reject_reason as reject_reason, o.code as order_code";

  private final DSLContext sql;

  /** 7.4.1.1 的调整单服务：**调用**它的批准 / 驳回 / 提交动作，不重复实现。 */
  private final InventoryAdjustmentService adjustments;

  public InventoryAdjustmentApprovalService(
      DSLContext sql, InventoryAdjustmentService adjustments) {
    this.sql = sql;
    this.adjustments = adjustments;
  }

  // ============================================================ 能力

  /**
   * 能力声明：逐条说清本模块**真的**能做什么、哪些维度归别的模块。
   *
   * <p>存在的理由与 6.3.1.3 一致：让界面能说清「为什么没有生效的审批规则」「为什么金额要申报」，
   * 而不是让它们显示成空白让人以为「这单没有这些值」。
   */
  public InventoryAdjustmentApprovalCapabilities capabilities() {
    long activeRules =
        sql.fetchOne(
                "select count(*) as c from inventory_adjustment_approval_rule"
                    + " where tenant_id = ? and status='ACTIVE'",
                tenant())
            .get("c", Long.class);
    long liveCases =
        sql.fetchOne(
                "select count(*) as c from inventory_adjustment_approval_case"
                    + " where tenant_id = ? and status <> 'REJECTED'",
                tenant())
            .get("c", Long.class);
    boolean gated = activeRules > 0;

    List<InventoryAdjustmentApprovalCapability> out = new ArrayList<>();
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "thresholdApproval",
            "按金额/数量阈值的审批规则",
            true,
            "7.4.1.2",
            "/inventory/adjustments",
            "阈值规则**自持**（租户级）：（调整单类型, 币种, 金额区间, 数量区间）⇒ 要求的审批层级与人数；"
                + "金额区间与数量区间都是**半开区间** [下限, 上限)，相邻规则既不重叠也不留缝。"
                + "数量区间量纲是**绝对调整量 Σ|qty_delta|**（盘盈与盘亏都计入动量）。"));
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "adjustmentTypeDimension",
            "按调整单类型（原因码）审批",
            true,
            "7.4.1.2",
            "/inventory/adjustments",
            "报废核销、盘盈补记、错发纠正的舞弊风险完全不同，所以原因码是规则的**一个维度**。"
                + "⚠️ 17 组织的审批规则服务**没有这一维**，所以类型维度在 warehouse 侧。"));
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "approverAuthorization",
            "审批人授权（层级 + 金额/数量区间）",
            true,
            "7.4.1.2",
            "/inventory/adjustments",
            "审批人表里登记：**审批层级** + **金额授权区间** + **数量授权上限**。"
                + "缺了区间就是「这个人什么都能批」—— 那不叫授权，叫没有闸门。"));
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "multiApprover",
            "多人会签",
            true,
            "7.4.1.2",
            "/inventory/adjustments",
            "规则命中后要求**审批人数**（1~5 人各批一次）；一个人批两次不能凑数（UNIQUE 兜底）。"
                + "人数没凑齐时数据库闸门会拒绝把调整单批成「已批」。"));
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "batchApproval",
            "批量审批 / 批量驳回",
            true,
            "7.4.1.2",
            "/inventory/adjustments",
            "一次提交多张调整单，逐条给结论（成功条数 + 失败原因），**不静默丢弃**失败的那几张；"
                + "一张失败用保存点回滚，不影响其它张。"));
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "rejectWithReason",
            "驳回并附意见",
            true,
            "7.4.1.2",
            "/inventory/adjustments",
            "驳回**必须**附意见（「驳回而不说为什么」等于没驳回）；意见进审批链路记录，事后可追溯。"));
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "approvalChain",
            "审批链路记录",
            true,
            "7.4.1.2",
            "/inventory/adjustments",
            "每一步记：谁批的、以什么层级、按哪条规则、批当时的金额与币种、意见是什么。"
                + "记录**追加式**（运行角色 UPDATE/DELETE 已收回 + 触发器再收一道）。"));
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "frozenDecision",
            "判定冻结（提交那一刻定档）",
            true,
            "7.4.1.2",
            "/inventory/adjustments",
            "「这张单要几级审批、几个人批」是**审计事实**，事后规则一改它不能跟着变 ⇒ "
                + "命中结果在提交那一刻冻结进审批单，驳回后重提会开**新的一条链**。"));
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "databaseGate",
            "数据库级阈值闸门",
            true,
            "7.4.1.2",
            "/inventory/adjustments",
            "V267 的触发器钉死：**待审 → 已批**这一步必须先过阈值判定"
                + "（本租户存在生效规则时，审批人数必须凑齐）；"
                + "绕过审批中心直接调用 7.4.1.1 的批准端点会被数据库当场拒绝。"));
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "valuationAccounting",
            "调整的会计影响（登记额 / 生效额）",
            true,
            "7.4.1.2",
            "/inventory/adjustments",
            "调整单过账那一刻，**生效额 = 登记额**（V267 的触发器，与库存分录同一个事务）。"
                + "「登记 ≠ 生效」避免「还没入账就当钱已经动了」。"));
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "identityApproval",
            "17 组织的通用审批流",
            false,
            "17 组织（identity 服务）",
            "/identity/approval-rules",
            "本模块**不**把审批委托给 identity 的通用审批流：identity 的规则维度里"
                + "**没有「数量阈值」，也没有「调整单类型」**（只有金额 + 跨法人/跨部门/新供应商/"
                + "高风险商品），要表达就得改 identity 的规则模型 —— 那是另一个服务的写入所有权。"
                + "⇒ 本模块自持规则，边界清楚、判定唯一（6.3.1.3 采购单审批同口径）。"));
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "inventoryCostBasis",
            "库存成本口径（金额的系统取值）",
            false,
            "12 财务（finance 服务当前不存在）",
            "/inventory/adjustments",
            "warehouse 库里**没有任何成本列**（inventory_balance / stock_bucket 都没有金额），"
                + "12 财务服务也不存在 ⇒ 调整单没有原生金额。"
                + "本模块的金额是提交人**申报**、提交那一刻**冻结**、进审批链路的登记额；"
                + "真正的估值口径随 12 财务落地后经授权 API / event 衔接。"
                + "⚠️ 残余风险如实说明：申报值理论上可被低报以躲开金额档，"
                + "缓解是「冻结不可改 + 审批人看得到 + 申报人与审批人分离 + 申报进 append-only 记录」。"));
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "financeService",
            "12 财务的调整单据与税额",
            false,
            "12 财务（finance 服务当前不存在）",
            "/inventory/adjustments",
            "本仓 backend/services/ 下没有 finance 服务 ⇒ 本模块**不造税率表**、"
                + "不跨库读 finance、不建跨服务 FK 或视图。调整的最终对账留待 finance 落地后衔接。"));
    out.add(
        new InventoryAdjustmentApprovalCapability(
            "postExecution",
            "审批通过后的库存分录",
            true,
            "7.4.1.1 调整单管理",
            "/inventory/adjustments",
            "阈值判定通过 ⇒ 本模块调 7.4.1.1 的批准动作（必填理由 + 提交人≠审批人 + DB 状态机）；"
                + "**入账仍是独立的 /post 一步**（只有已批的单据能动余额，"
                + "V260 的入账闸门在数据库层再挡一次）。"));

    return new InventoryAdjustmentApprovalCapabilities(
        out,
        new InventoryAdjustmentApprovalPolicy(
            gated,
            activeRules,
            liveCases,
            gated
                ? "阈值闸门**已启用**：本租户存在生效中的阈值规则，批准必须走本模块的审批链"
                    + "（数据库触发器会拒绝绕过阈值直接批准的那条路径）。"
                : "阈值闸门**未启用**：本租户还没有生效中的阈值规则，"
                    + "此时「待审 → 已批」不受阈值档约束，但 7.4.1.1 的基础管控"
                    + "（提交人≠审批人的 DB CHECK + 必填理由 + 状态机 + 入账闸门）**一道不少**。"),
        "本模块负责**阈值判定**，不负责「批准」这个动作本身 —— 批准走 7.4.1.1 的显式端点，"
            + "本模块在它前面接一道判定，判定通过才放行。");
  }

  // ============================================================ 阈值规则

  /** 规则列表（租户级配置，数量有限 ⇒ 一次返回全部，不假装分页）。 */
  public List<InventoryAdjustmentApprovalRule> rules() {
    List<InventoryAdjustmentApprovalRule> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,rule_no,rule_name,reason_code,currency,min_amount,max_amount,min_qty,max_qty,"
                + "required_tier,required_approvals,priority,status,note,version,created_at,updated_at"
                + " from inventory_adjustment_approval_rule where tenant_id = ?"
                + " order by priority asc, created_at asc, id asc",
            tenant())) {
      out.add(ruleView(r));
    }
    return out;
  }

  @Transactional
  public InventoryAdjustmentApprovalRule createRule(
      InventoryAdjustmentApprovalRuleInput input, UUID actorId) {
    var rule = normalizeRule(input);
    if (sql.fetchOne(
            "select 1 from inventory_adjustment_approval_rule where tenant_id = ? and rule_no = ?",
            tenant(),
            rule.ruleNo())
        != null) {
      throw conflict("规则号 " + rule.ruleNo() + " 已存在：规则号在租户内必须唯一，"
          + "否则「这条规则是哪一条」没有答案。");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime now = dbNow();
    sql.execute(
        "insert into inventory_adjustment_approval_rule(id,tenant_id,rule_no,rule_name,reason_code,"
            + "currency,min_amount,max_amount,min_qty,max_qty,required_tier,required_approvals,"
            + "priority,status,note,created_by,created_at,updated_at,version)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?::timestamptz,?::timestamptz,0)",
        id,
        tenant(),
        rule.ruleNo(),
        rule.ruleName(),
        rule.reasonCode(),
        rule.currency(),
        rule.minAmount(),
        rule.maxAmount(),
        rule.minQty(),
        rule.maxQty(),
        rule.requiredTier(),
        rule.requiredApprovals(),
        rule.priority(),
        rule.note(),
        actorId,
        now,
        now);
    return requireRule(id);
  }

  @Transactional
  public InventoryAdjustmentApprovalRule updateRule(
      UUID ruleId, InventoryAdjustmentApprovalRuleInput input, UUID actorId) {
    var rule = normalizeRule(input);
    var existing = requireRuleRow(ruleId);
    if (!existing.get("rule_no", String.class).equals(rule.ruleNo())
        && sql.fetchOne(
                "select 1 from inventory_adjustment_approval_rule where tenant_id = ? and rule_no = ?",
                tenant(),
                rule.ruleNo())
            != null) {
      throw conflict("规则号 " + rule.ruleNo() + " 已存在：规则号在租户内必须唯一。");
    }
    sql.execute(
        "update inventory_adjustment_approval_rule set rule_no=?,rule_name=?,reason_code=?,currency=?,"
            + "min_amount=?,max_amount=?,min_qty=?,max_qty=?,required_tier=?,required_approvals=?,"
            + "priority=?,note=?,updated_at=?::timestamptz,version=version+1"
            + " where tenant_id = ? and id = ?",
        rule.ruleNo(),
        rule.ruleName(),
        rule.reasonCode(),
        rule.currency(),
        rule.minAmount(),
        rule.maxAmount(),
        rule.minQty(),
        rule.maxQty(),
        rule.requiredTier(),
        rule.requiredApprovals(),
        rule.priority(),
        rule.note(),
        dbNow(),
        tenant(),
        ruleId);
    return requireRule(ruleId);
  }

  @Transactional
  public InventoryAdjustmentApprovalRule setRuleStatus(
      UUID ruleId, String status, UUID actorId) {
    requireRuleRow(ruleId);
    String next = requireStatus(status);
    sql.execute(
        "update inventory_adjustment_approval_rule set status=?,updated_at=?::timestamptz,"
            + "version=version+1 where tenant_id = ? and id = ?",
        next,
        dbNow(),
        tenant(),
        ruleId);
    return requireRule(ruleId);
  }

  // ============================================================ 审批人授权

  /** 审批人授权列表。 */
  public List<InventoryAdjustmentApprovalApprover> approvers() {
    List<InventoryAdjustmentApprovalApprover> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,user_id,user_name,approval_tier,currency,min_amount,max_amount,max_qty,"
                + "status,note,created_at from inventory_adjustment_approval_approver"
                + " where tenant_id = ? order by approval_tier asc, user_name asc, id asc",
            tenant())) {
      out.add(approverView(r));
    }
    return out;
  }

  @Transactional
  public InventoryAdjustmentApprovalApprover registerApprover(
      InventoryAdjustmentApprovalApproverInput input, UUID actorId) {
    String tier = requireTier(input.approvalTier());
    String currency = requireCurrency(input.currency());
    BigDecimal minAmount = amount(input.minAmount(), "0");
    BigDecimal maxAmount = input.maxAmount() == null ? null : amount(input.maxAmount(), "0");
    BigDecimal maxQty = input.maxQty() == null ? null : amount(input.maxQty(), "0");
    if (maxAmount != null && maxAmount.compareTo(minAmount) <= 0) {
      throw bad("金额授权区间必须满足 下限 < 上限（上限留空 = 不限）。");
    }
    if (maxQty != null && maxQty.signum() < 0) {
      throw bad("数量授权上限不能为负。");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime now = dbNow();
    sql.execute(
        "insert into inventory_adjustment_approval_approver(id,tenant_id,user_id,user_name,"
            + "approval_tier,currency,min_amount,max_amount,max_qty,status,note,created_by,"
            + "created_at,updated_at,version)"
            + " values(?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?::timestamptz,?::timestamptz,0)",
        id,
        tenant(),
        input.userId(),
        nullToEmpty(input.userName()),
        tier,
        currency,
        minAmount,
        maxAmount,
        maxQty,
        nullToEmpty(input.note()),
        actorId,
        now,
        now);
    return requireApprover(id);
  }

  @Transactional
  public InventoryAdjustmentApprovalApprover setApproverStatus(
      UUID approverId, String status, UUID actorId) {
    requireApproverRow(approverId);
    String next = requireStatus(status);
    sql.execute(
        "update inventory_adjustment_approval_approver set status=?,updated_at=?::timestamptz,"
            + "version=version+1 where tenant_id = ? and id = ?",
        next,
        dbNow(),
        tenant(),
        approverId);
    return requireApprover(approverId);
  }

  // ============================================================ 待我审批

  /**
   * 待我审批工作台（真分页）。
   *
   * <p>每条都带 {@code approvable} 与 {@code approvableReason}：不该由我批的（层级不够、
   * 超出授权区间、已经批过、本人提交的）**显示出来但标成不可批并写清原因** ——
   * 直接不显示会让人以为「这单没人管」。
   */
  public InventoryAdjustmentApprovalPendingPage pending(UUID actorId, int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), 200);
    long total =
        sql.fetchOne(
                "select count(*) as c from inventory_adjustment_approval_case"
                    + " where tenant_id = ? and status='PENDING'",
                tenant())
            .get("c", Long.class);
    List<InventoryAdjustmentApprovalPendingItem> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select "
                + CASE_COLUMNS
                + ", o.status as order_status,"
                + " (select count(*) from inventory_adjustment_order_line l"
                + "   where l.tenant_id = c.tenant_id and l.order_id = c.order_id) as line_count,"
                + " (select coalesce(sum(l.qty_delta),0)::numeric(19,4)"
                + "   from inventory_adjustment_order_line l"
                + "   where l.tenant_id = c.tenant_id and l.order_id = c.order_id) as net_qty,"
                + " r.label as reason_label"
                + " from inventory_adjustment_approval_case c"
                + " join inventory_adjustment_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + " left join inventory_adjustment_reason r on r.code = c.reason_code"
                + " where c.tenant_id = ? and c.status='PENDING'"
                + " order by c.submitted_at asc, c.id asc limit ? offset ?",
            tenant(),
            safeSize,
            safePage * safeSize)) {
      var approvalCase = caseView(r);
      String reason = approvableReason(approvalCase, actorId, oSubmittedBy(r));
      items.add(
          new InventoryAdjustmentApprovalPendingItem(
              approvalCase,
              r.get("order_code", String.class),
              r.get("order_status", String.class),
              r.get("reason_code", String.class),
              nullToEmpty(r.get("reason_label", String.class)),
              r.get("line_count", Long.class) == null ? 0L : r.get("line_count", Long.class),
              scale(r.get("net_qty", BigDecimal.class)),
              scale(r.get("order_qty", BigDecimal.class)),
              r.get("submitted_at", OffsetDateTime.class),
              reason.isEmpty(),
              reason.isEmpty() ? "可以批" : reason));
    }
    return new InventoryAdjustmentApprovalPendingPage(items, total, safePage, safeSize);
  }

  // ============================================================ 阈值判定（只读试算）

  /**
   * 阈值判定（只读，不落库）。
   *
   * <p>试算用调用方给的金额 / 币种；不给就用该单已登记的登记额。
   * 「提交那一刻冻结」由 {@link #submit} 负责，这里只是让用户在提交前看到会命中哪一档。
   */
  public InventoryAdjustmentApprovalEvaluation evaluate(
      InventoryAdjustmentApprovalEvaluateInput input, UUID actorId) {
    var order = requireOrderRow(input.orderId());
    BigDecimal qty = absoluteQty(input.orderId());
    BigDecimal evalAmount =
        input.amount() == null ? registeredAmount(input.orderId()) : amount(input.amount(), "0");
    String currency =
        notBlank(input.currency()) ? requireCurrency(input.currency()) : registeredCurrency(input.orderId());
    if (evalAmount == null || evalAmount.signum() <= 0) {
      throw bad("阈值判定需要金额：这单还没有登记过金额，"
          + "请带上 amount + currency 试算（提交待审时这两个字段是必填的）。");
    }
    String reasonCode = order.get("reason_code", String.class);
    var match = matchRule(reasonCode, evalAmount, currency, qty);
    var live = liveCase(input.orderId());
    long approvedCount = live == null ? 0 : live.get("approved_count", Integer.class);
    String caseStatus = live == null ? null : live.get("status", String.class);
    String requiredTier = match.get("required_tier", String.class);
    int requiredApprovals = match.get("required_approvals", Integer.class);
    String reason =
        live == null
            ? "这张单还没有提交待审：先走 /adjustment-approvals/{id}/submit 冻结判定。"
            : approvableReason(
                caseView(live), actorId, order.get("submitted_by_user_id", UUID.class));
    return new InventoryAdjustmentApprovalEvaluation(
        input.orderId(),
        order.get("code", String.class),
        order.get("status", String.class),
        reasonCode,
        scale(qty),
        scale(order.get("net_qty", BigDecimal.class)),
        evalAmount,
        currency,
        match.get("rule_match", String.class),
        match.get("rule_id", UUID.class),
        nullToEmpty(match.get("rule_name", String.class)),
        requiredTier,
        requiredApprovals,
        approvedCount,
        caseStatus,
        reason.isEmpty(),
        reason.isEmpty() ? "可以批" : reason);
  }

  // ============================================================ 提交待审（冻结判定）

  /**
   * 提交待审：**冻结阈值判定 + 登记估值**，然后调 7.4.1.1 的提交动作。
   *
   * <p>一个事务里做四件事（顺序不可换）：
   *
   * <ol>
   *   <li>登记估值（登记额 + 币种）：**提交那一刻冻结**，此后不可改；
   *   <li>调判定函数冻结命中结果（规则 / 层级 / 人数）；
   *   <li>开一条审批单（{@code attempt_no} = 第几次提交，驳回后重提开新的一条链）；
   *   <li>调 7.4.1.1 的提交动作把调整单推到 {@code PENDING_APPROVAL}。
   * </ol>
   *
   * <p>金额必填：没有金额就没有「金额阈值」这一说，而**不申报金额就能躲开金额档**
   * 是一个必须堵掉的口子。
   */
  @Transactional
  public InventoryAdjustmentApprovalCaseDetail submit(
      UUID orderId, InventoryAdjustmentApprovalSubmitInput input, UUID actorId) {
    var order = requireOrderRow(orderId);
    if (!"DRAFT".equals(order.get("status", String.class))) {
      throw conflict(
          "调整单 "
              + order.get("code", String.class)
              + " 当前是「"
              + order.get("status", String.class)
              + "」，只有草稿能提交待审。");
    }
    long lines =
        sql.fetchOne(
                "select count(*) as c from inventory_adjustment_order_line"
                    + " where tenant_id = ? and order_id = ?",
                tenant(),
                orderId)
            .get("c", Long.class);
    if (lines == 0) {
      throw bad("调整单没有明细行：一张零行的单据会让台账上看起来像「处理过了」。");
    }
    BigDecimal amount = amount(input.amount(), "0");
    if (amount.signum() <= 0) {
      throw bad("必须申报调整的估值金额（大于 0）："
          + "金额阈值与审批人的金额授权区间都以它为准，"
          + "「不申报就能躲开金额档」是不能接受的。");
    }
    String currency = requireCurrency(input.currency());
    BigDecimal qty = absoluteQty(orderId);
    String reasonCode = order.get("reason_code", String.class);
    OffsetDateTime now = dbNow();

    // 1) 估值登记（登记额冻结）。驳回后重提会覆盖登记额 —— 生效额此时恒为 0。
    sql.execute(
        "insert into inventory_adjustment_valuation(id,tenant_id,order_id,registered_amount,currency,"
            + "effective_amount,status,registered_by,registered_at,created_at)"
            + " values(?,?,?,?,?,0,'REGISTERED',?,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id,order_id) do update set registered_amount = excluded.registered_amount,"
            + " currency = excluded.currency, registered_by = excluded.registered_by,"
            + " registered_at = excluded.registered_at",
        UUID.randomUUID(),
        tenant(),
        orderId,
        amount,
        currency,
        actorId,
        now,
        now);

    // 2) 冻结判定（DB 函数是唯一实现，服务端与数据库不会漂移）。
    var match = matchRule(reasonCode, amount, currency, qty);

    // 3) 开一条审批单。
    int attempt =
        sql.fetchOne(
                "select coalesce(max(attempt_no),0)+1 as n from inventory_adjustment_approval_case"
                    + " where tenant_id = ? and order_id = ?",
                tenant(),
                orderId)
            .get("n", Integer.class);
    UUID caseId = UUID.randomUUID();
    sql.execute(
        "insert into inventory_adjustment_approval_case(id,tenant_id,order_id,case_no,attempt_no,"
            + "rule_id,rule_name,rule_match,reason_code,order_amount,currency,order_qty,"
            + "required_tier,required_approvals,approved_count,status,submitted_by,submitted_at,"
            + "created_at,updated_at,version)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,'PENDING',?,?::timestamptz,"
            + "?::timestamptz,?::timestamptz,0)",
        caseId,
        tenant(),
        orderId,
        nextCaseNo(now),
        attempt,
        match.get("rule_id", UUID.class),
        nullToEmpty(match.get("rule_name", String.class)),
        match.get("rule_match", String.class),
        reasonCode,
        amount,
        currency,
        qty,
        match.get("required_tier", String.class),
        match.get("required_approvals", Integer.class),
        actorId,
        now,
        now,
        now);

    insertRecord(
        caseId,
        orderId,
        "SUBMITTED",
        actorId,
        now,
        match.get("required_tier", String.class),
        amount,
        currency,
        qty,
        "提交待审：命中 "
            + match.get("rule_match", String.class)
            + " 规则「"
            + (match.get("rule_id", UUID.class) == null
                ? "（无 ⇒ 默认档 L1 / 1 人）"
                : nullToEmpty(match.get("rule_name", String.class)))
            + "」，要求 "
            + match.get("required_tier", String.class)
            + " 级 "
            + match.get("required_approvals", Integer.class)
            + " 人审批。申报金额 "
            + amount.toPlainString()
            + " "
            + currency
            + "，绝对调整量 "
            + qty.toPlainString()
            + "。**余额此刻仍未改动**。");

    // 4) 调 7.4.1.1 的提交动作（DRAFT → PENDING_APPROVAL）。
    adjustments.submit(
        orderId, new InventoryAdjustmentModels.InventoryAdjustmentNoteInput(nullToEmpty(input.note())), actorId);

    return caseDetail(orderId);
  }

  // ============================================================ 审批单详情

  /** 审批单详情 = 审批单 + 全部链路记录（含历史被驳回的那几版）。 */
  public InventoryAdjustmentApprovalCaseDetail caseDetail(UUID orderId) {
    var live = liveCase(orderId);
    Record row = live;
    if (row == null) {
      // 没有活着的审批单：退回最近的一版（多半是被驳回的那一版）。
      row =
          sql.fetchOne(
              "select "
                  + CASE_COLUMNS
                  + " from inventory_adjustment_approval_case c"
                  + " join inventory_adjustment_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                  + " where c.tenant_id = ? and c.order_id = ?"
                  + " order by c.attempt_no desc limit 1",
              tenant(),
              orderId);
    }
    if (row == null) {
      throw notFound("调整单的审批单", orderId);
    }
    return new InventoryAdjustmentApprovalCaseDetail(caseView(row), records(orderId));
  }

  private List<InventoryAdjustmentApprovalRecord> records(UUID orderId) {
    List<InventoryAdjustmentApprovalRecord> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,case_id,order_id,action,actor_user_id,occurred_at,approval_tier,"
                + "order_amount,currency,order_qty,detail from inventory_adjustment_approval_record"
                + " where tenant_id = ? and order_id = ? order by occurred_at asc, id asc",
            tenant(),
            orderId)) {
      out.add(recordView(r));
    }
    return out;
  }

  // ============================================================ 批准 / 驳回

  /**
   * 批准：阈值判定 → 记审批链路 → 人数凑齐则调 7.4.1.1 的批准动作。
   *
   * <p><b>批准这一动作本身归 7.4.1.1</b>（必填理由 + 提交人≠审批人 + DB 状态机）。
   * 本模块在它前面加一道：层级够不够、金额与数量在不在授权区间、是不是自己提的单、
   * 是不是已经批过 —— 四道都过，才放行。
   */
  @Transactional
  public InventoryAdjustmentApprovalCaseDetail approve(UUID orderId, String note, UUID actorId) {
    var order = requireOrderRow(orderId);
    if (!"PENDING_APPROVAL".equals(order.get("status", String.class))) {
      throw conflict(
          "调整单 "
              + order.get("code", String.class)
              + " 当前是「"
              + order.get("status", String.class)
              + "」，只有待审批的单据才能批准。");
    }
    var live = liveCase(orderId);
    if (live == null) {
      throw conflict("这张调整单没有审批单：批准必须先走 /adjustment-approvals/{id}/submit"
          + "（提交那一刻冻结阈值判定）。");
    }
    var approvalCase = caseView(live);
    String reason = approvableReason(approvalCase, actorId, order.get("submitted_by_user_id", UUID.class));
    if (!reason.isEmpty()) {
      throw conflict(reason);
    }
    String decision = requireText(note, "批准必须写理由："
        + "只留一个「已批」状态，事后没人说得清批的是哪一条判断。");
    OffsetDateTime now = dbNow();

    insertRecord(
        approvalCase.id(),
        orderId,
        "APPROVED",
        actorId,
        now,
        approvalCase.requiredTier(),
        approvalCase.orderAmount(),
        approvalCase.currency(),
        approvalCase.orderQty(),
        "批准调整单 " + order.get("code", String.class) + " · " + decision);

    int count = approvalCase.approvedCount() + 1;
    boolean complete = count >= approvalCase.requiredApprovals();
    sql.execute(
        "update inventory_adjustment_approval_case set approved_count=?,status=?,updated_at=?::timestamptz,"
            + "version=version+1 where tenant_id = ? and id = ? and status='PENDING'",
        count,
        complete ? "APPROVED" : "PENDING",
        now,
        tenant(),
        approvalCase.id());

    if (complete) {
      // 人数凑齐 ⇒ 放行到 7.4.1.1 的批准动作（DB 闸门会再核一次人数）。
      adjustments.approve(
          orderId, new InventoryAdjustmentModels.InventoryAdjustmentNoteInput(decision), actorId);
    }
    return caseDetail(orderId);
  }

  /**
   * 驳回：阈值判定 → 记审批链路 → 调 7.4.1.1 的驳回动作。
   *
   * <p>意见必填。驳回后该审批单作废（{@code status='REJECTED'}），改完重提会开**新的一条链**
   * （历史判定与链路记录不被改写）。
   */
  @Transactional
  public InventoryAdjustmentApprovalCaseDetail reject(UUID orderId, String note, UUID actorId) {
    var order = requireOrderRow(orderId);
    if (!"PENDING_APPROVAL".equals(order.get("status", String.class))) {
      throw conflict(
          "调整单 "
              + order.get("code", String.class)
              + " 当前是「"
              + order.get("status", String.class)
              + "」，只有待审批的单据才能驳回。");
    }
    var live = liveCase(orderId);
    if (live == null) {
      throw conflict("这张调整单没有审批单：驳回必须先走 /adjustment-approvals/{id}/submit。");
    }
    var approvalCase = caseView(live);
    String reason = approvableReason(approvalCase, actorId, order.get("submitted_by_user_id", UUID.class));
    if (!reason.isEmpty()) {
      throw conflict(reason);
    }
    String decision = requireText(note, "驳回必须写意见：只给一个 REJECTED 状态，提交的人无法知道该改什么。");
    OffsetDateTime now = dbNow();

    insertRecord(
        approvalCase.id(),
        orderId,
        "REJECTED",
        actorId,
        now,
        approvalCase.requiredTier(),
        approvalCase.orderAmount(),
        approvalCase.currency(),
        approvalCase.orderQty(),
        "驳回调整单 " + order.get("code", String.class) + " · " + decision);

    sql.execute(
        "update inventory_adjustment_approval_case set status='REJECTED',rejected_by=?,"
            + "rejected_at=?::timestamptz,reject_reason=?,updated_at=?::timestamptz,version=version+1"
            + " where tenant_id = ? and id = ? and status='PENDING'",
        actorId,
        now,
        decision,
        now,
        tenant(),
        approvalCase.id());

    adjustments.reject(
        orderId, new InventoryAdjustmentModels.InventoryAdjustmentNoteInput(decision), actorId);
    return caseDetail(orderId);
  }

  // ============================================================ 批量

  /**
   * 批量审批 / 批量驳回。
   *
   * <p>🔴 <b>逐条给结论</b>：一张批不过的不影响其它张；失败原因逐条返回，
   * <b>绝不</b>静默丢弃（「丢掉的调整单 = 没人看的调整单」）。
   *
   * <p>🔴 <b>保存点</b>：一张失败不能把它的半截写入留在事务里 ——
   * 否则「审批留痕已写、审批单没推进」这种不一致会被提交进库。
   * 回滚到保存点 = 这一张单相当于没发生过，其它张不受影响。
   */
  public InventoryAdjustmentApprovalBatchResult batch(
      InventoryAdjustmentApprovalBatchInput input, UUID actorId, boolean approve) {
    if (input == null || input.orderIds() == null || input.orderIds().isEmpty()) {
      throw bad("批量审批至少要选中一张调整单");
    }
    if (input.orderIds().size() > 200) {
      throw bad("一次最多批量处理 200 张调整单");
    }
    String note = nullToEmpty(input.note()).trim();
    if (!approve && note.isEmpty()) {
      throw bad("批量驳回必须附意见");
    }

    List<InventoryAdjustmentApprovalBatchItem> items = new ArrayList<>();
    int ok = 0;
    for (UUID orderId : input.orderIds()) {
      String orderCode = "";
      sql.execute(sql.savepoint(SAVEPOINT));
      try {
        var order = requireOrderRow(orderId);
        orderCode = order.get("code", String.class);
        if (approve) {
          approve(orderId, note, actorId);
        } else {
          reject(orderId, note, actorId);
        }
        items.add(new InventoryAdjustmentApprovalBatchItem(orderId, orderCode, true, "已处理"));
        ok++;
      } catch (RuntimeException e) {
        items.add(
            new InventoryAdjustmentApprovalBatchItem(orderId, orderCode, false, failureMessage(e)));
        rollbackTo(SAVEPOINT);
      } finally {
        releaseQuietly(SAVEPOINT);
      }
    }
    return new InventoryAdjustmentApprovalBatchResult(items, ok, items.size() - ok);
  }

  // ============================================================ 会计影响台账

  /** 调整的会计影响台账（真分页）：登记额 / 生效额分两列。 */
  public InventoryAdjustmentApprovalValuationPage valuations(
      String status, int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), 200);
    String filter = notBlank(status) ? " and v.status = ?" : "";
    List<Object> binds = new ArrayList<>();
    binds.add(tenant());
    if (notBlank(status)) {
      binds.add(requireStatus(status));
    }
    binds.add(safeSize);
    binds.add(safePage * safeSize);
    long total =
        sql.fetchOne(
                "select count(*) as c from inventory_adjustment_valuation v"
                    + " where v.tenant_id = ?"
                    + filter,
                binds.toArray())
            .get("c", Long.class);
    List<InventoryAdjustmentApprovalValuation> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select v.id,v.order_id,v.registered_amount,v.currency,v.effective_amount,v.status,"
                + "v.registered_by,v.registered_at,v.effective_at,o.code as order_code"
                + " from inventory_adjustment_valuation v"
                + " join inventory_adjustment_order o on o.tenant_id = v.tenant_id and o.id = v.order_id"
                + " where v.tenant_id = ?"
                + filter
                + " order by v.registered_at desc, v.id desc limit ? offset ?",
            binds.toArray())) {
      items.add(
          new InventoryAdjustmentApprovalValuation(
              r.get("id", UUID.class),
              r.get("order_id", UUID.class),
              r.get("order_code", String.class),
              scale(r.get("registered_amount", BigDecimal.class)),
              r.get("currency", String.class),
              scale(r.get("effective_amount", BigDecimal.class)),
              r.get("status", String.class),
              r.get("registered_by", UUID.class),
              r.get("registered_at", OffsetDateTime.class),
              r.get("effective_at", OffsetDateTime.class)));
    }
    return new InventoryAdjustmentApprovalValuationPage(items, total, safePage, safeSize);
  }

  // ============================================================ 判定与授权

  /** 调 DB 里的判定函数（**唯一实现**）。 */
  private Record matchRule(
      String reasonCode, BigDecimal amount, String currency, BigDecimal qty) {
    return sql.fetchOne(
        "select rule_id,rule_name,rule_match,reason_code,order_amount,currency,order_qty,"
            + "required_tier,required_approvals from inventory_adjustment_approval_match(?,?,?,?,?)",
        tenant(),
        reasonCode,
        amount,
        currency,
        qty);
  }

  /**
   * 当前这个人此刻能不能批这张单；不能批时返回**原因**（空串 = 可以批）。
   *
   * <p>四道闸：① 不是提交人（自提自批 = 没有审批）；② 有生效中的授权；
   * ③ 层级够 + 金额在授权区间 + 数量在授权上限；④ 本轮没批过（不能一个人批两次凑数）。
   */
  private String approvableReason(
      InventoryAdjustmentApprovalCase approvalCase, UUID actorId, UUID submittedBy) {
    if (submittedBy != null && submittedBy.equals(actorId)) {
      return "提交人不能批自己提的单（自提自批的审批与没有审批是同一件事，"
          + "而库存调整正是舞弊高发区）。";
    }
    if (approvalCase.status() != null && !"PENDING".equals(approvalCase.status())) {
      return "这张审批单当前是「" + approvalCase.status() + "」，不在待审批状态。";
    }
    boolean already =
        sql.fetchOne(
                "select 1 from inventory_adjustment_approval_record where tenant_id = ? and case_id = ?"
                    + " and action='APPROVED' and actor_user_id = ?",
                tenant(),
                approvalCase.id(),
                actorId)
            != null;
    if (already) {
      return "你已经在这一轮批过这张单了：一个人批两次不能凑人数（" + approvalCase.approvedCount() + "/"
          + approvalCase.requiredApprovals() + "）。";
    }
    var grants =
        sql.fetch(
            "select approval_tier,currency,min_amount,max_amount,max_qty"
                + " from inventory_adjustment_approval_approver"
                + " where tenant_id = ? and user_id = ? and status='ACTIVE'",
            tenant(),
            actorId);
    if (grants.isEmpty()) {
      return "你没有生效中的审批授权：审批人要先登记（层级 + 金额授权区间 + 数量授权上限）。";
    }
    boolean tierOk = false;
    boolean amountOk = false;
    boolean qtyOk = false;
    for (Record g : grants) {
      if (tierRank(g.get("approval_tier", String.class)) >= tierRank(approvalCase.requiredTier())) {
        tierOk = true;
      }
      String grantCurrency = g.get("currency", String.class);
      BigDecimal min = g.get("min_amount", BigDecimal.class);
      BigDecimal max = g.get("max_amount", BigDecimal.class);
      if ("ANY".equals(grantCurrency) || grantCurrency.equals(approvalCase.currency())) {
        if (approvalCase.orderAmount().compareTo(min) >= 0
            && (max == null || approvalCase.orderAmount().compareTo(max) < 0)) {
          amountOk = true;
        }
      }
      BigDecimal maxQty = g.get("max_qty", BigDecimal.class);
      if (maxQty == null || approvalCase.orderQty().compareTo(maxQty) <= 0) {
        qtyOk = true;
      }
    }
    if (!tierOk) {
      return "你的审批层级不够：这张单要求 " + approvalCase.requiredTier() + " 级。";
    }
    if (!amountOk) {
      return "金额 " + approvalCase.orderAmount().toPlainString() + " " + approvalCase.currency()
          + " 超出你的授权区间。";
    }
    if (!qtyOk) {
      return "绝对调整量 " + approvalCase.orderQty().toPlainString() + " 超出你的数量授权上限。";
    }
    return "";
  }

  private static int tierRank(String tier) {
    return switch (tier) {
      case "L3" -> 3;
      case "L2" -> 2;
      default -> 1;
    };
  }

  // ============================================================ 查询小工具

  private Record requireOrderRow(UUID orderId) {
    var row =
        sql.fetchOne(
            "select a.id,a.code,a.status,a.reason_code,a.note,a.submitted_by_user_id,a.submitted_at,"
                + "count(l.id) as line_count, coalesce(sum(l.qty_delta),0)::numeric(19,4) as net_qty"
                + " from inventory_adjustment_order a"
                + " left join inventory_adjustment_order_line l on l.tenant_id = a.tenant_id"
                + " and l.order_id = a.id where a.tenant_id = ? and a.id = ? group by a.id",
            tenant(),
            orderId);
    if (row == null) {
      throw notFound("调整单", orderId);
    }
    return row;
  }

  /** 绝对调整量 Σ|qty_delta|（盘盈与盘亏都计入「动量」）。 */
  private BigDecimal absoluteQty(UUID orderId) {
    return scale(
        sql.fetchOne(
                "select coalesce(sum(abs(qty_delta)),0)::numeric(19,4) as q"
                    + " from inventory_adjustment_order_line where tenant_id = ? and order_id = ?",
                tenant(),
                orderId)
            .get("q", BigDecimal.class));
  }

  private BigDecimal registeredAmount(UUID orderId) {
    var row =
        sql.fetchOne(
            "select registered_amount from inventory_adjustment_valuation"
                + " where tenant_id = ? and order_id = ?",
            tenant(),
            orderId);
    return row == null ? null : row.get("registered_amount", BigDecimal.class);
  }

  private String registeredCurrency(UUID orderId) {
    var row =
        sql.fetchOne(
            "select currency from inventory_adjustment_valuation where tenant_id = ? and order_id = ?",
            tenant(),
            orderId);
    return row == null ? null : row.get("currency", String.class);
  }

  /** 活着的审批单（没被驳回的那一版）。 */
  private Record liveCase(UUID orderId) {
    return sql.fetchOne(
        "select "
            + CASE_COLUMNS
            + " from inventory_adjustment_approval_case c"
            + " join inventory_adjustment_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
            + " where c.tenant_id = ? and c.order_id = ? and c.status <> 'REJECTED'",
        tenant(),
        orderId);
  }

  private Record requireRuleRow(UUID ruleId) {
    var row =
        sql.fetchOne(
            "select * from inventory_adjustment_approval_rule where tenant_id = ? and id = ?",
            tenant(),
            ruleId);
    if (row == null) {
      throw notFound("阈值规则", ruleId);
    }
    return row;
  }

  private InventoryAdjustmentApprovalRule requireRule(UUID ruleId) {
    return ruleView(requireRuleRow(ruleId));
  }

  private Record requireApproverRow(UUID approverId) {
    var row =
        sql.fetchOne(
            "select * from inventory_adjustment_approval_approver where tenant_id = ? and id = ?",
            tenant(),
            approverId);
    if (row == null) {
      throw notFound("审批人授权", approverId);
    }
    return row;
  }

  private InventoryAdjustmentApprovalApprover requireApprover(UUID approverId) {
    return approverView(requireApproverRow(approverId));
  }

  private void insertRecord(
      UUID caseId,
      UUID orderId,
      String action,
      UUID actorId,
      OffsetDateTime at,
      String tier,
      BigDecimal amount,
      String currency,
      BigDecimal qty,
      String detail) {
    sql.execute(
        "insert into inventory_adjustment_approval_record(id,tenant_id,case_id,order_id,action,"
            + "actor_user_id,occurred_at,approval_tier,order_amount,currency,order_qty,detail,created_at)"
            + " values(?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenant(),
        caseId,
        orderId,
        action,
        actorId,
        at,
        tier,
        amount,
        currency,
        qty,
        detail,
        at);
  }

  // ============================================================ 视图

  private static InventoryAdjustmentApprovalRule ruleView(Record r) {
    return new InventoryAdjustmentApprovalRule(
        r.get("id", UUID.class),
        r.get("rule_no", String.class),
        r.get("rule_name", String.class),
        r.get("reason_code", String.class),
        r.get("currency", String.class),
        scale(r.get("min_amount", BigDecimal.class)),
        r.get("max_amount", BigDecimal.class),
        scale(r.get("min_qty", BigDecimal.class)),
        r.get("max_qty", BigDecimal.class),
        r.get("required_tier", String.class),
        r.get("required_approvals", Integer.class),
        r.get("priority", Integer.class),
        r.get("status", String.class),
        nullToEmpty(r.get("note", String.class)),
        r.get("version", Long.class) == null ? 0L : r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static InventoryAdjustmentApprovalApprover approverView(Record r) {
    return new InventoryAdjustmentApprovalApprover(
        r.get("id", UUID.class),
        r.get("user_id", UUID.class),
        nullToEmpty(r.get("user_name", String.class)),
        r.get("approval_tier", String.class),
        r.get("currency", String.class),
        scale(r.get("min_amount", BigDecimal.class)),
        r.get("max_amount", BigDecimal.class),
        r.get("max_qty", BigDecimal.class),
        r.get("status", String.class),
        nullToEmpty(r.get("note", String.class)),
        r.get("created_at", OffsetDateTime.class));
  }

  private static InventoryAdjustmentApprovalCase caseView(Record r) {
    return new InventoryAdjustmentApprovalCase(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        r.get("order_code", String.class),
        r.get("case_no", String.class),
        r.get("attempt_no", Integer.class),
        r.get("rule_id", UUID.class),
        nullToEmpty(r.get("rule_name", String.class)),
        r.get("rule_match", String.class),
        r.get("reason_code", String.class),
        scale(r.get("order_amount", BigDecimal.class)),
        r.get("currency", String.class),
        scale(r.get("order_qty", BigDecimal.class)),
        r.get("required_tier", String.class),
        r.get("required_approvals", Integer.class),
        r.get("approved_count", Integer.class),
        r.get("status", String.class),
        r.get("submitted_by", UUID.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("rejected_by", UUID.class),
        r.get("rejected_at", OffsetDateTime.class),
        nullToEmpty(r.get("reject_reason", String.class)));
  }

  private static InventoryAdjustmentApprovalRecord recordView(Record r) {
    String action = r.get("action", String.class);
    return new InventoryAdjustmentApprovalRecord(
        r.get("id", UUID.class),
        r.get("case_id", UUID.class),
        r.get("order_id", UUID.class),
        action,
        actionLabel(action),
        r.get("actor_user_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("approval_tier", String.class),
        scale(r.get("order_amount", BigDecimal.class)),
        r.get("currency", String.class),
        scale(r.get("order_qty", BigDecimal.class)),
        nullToEmpty(r.get("detail", String.class)));
  }

  private static String actionLabel(String action) {
    return switch (action) {
      case "SUBMITTED" -> "提交待审";
      case "APPROVED" -> "批准";
      case "REJECTED" -> "驳回";
      case "EVALUATED" -> "阈值判定";
      default -> action;
    };
  }

  // ============================================================ 归一化与小工具

  private record NormalizedRule(
      String ruleNo,
      String ruleName,
      String reasonCode,
      String currency,
      BigDecimal minAmount,
      BigDecimal maxAmount,
      BigDecimal minQty,
      BigDecimal maxQty,
      String requiredTier,
      int requiredApprovals,
      int priority,
      String note) {}

  private NormalizedRule normalizeRule(InventoryAdjustmentApprovalRuleInput input) {
    String ruleNo = requireText(input.ruleNo(), "规则号不能为空");
    String ruleName = requireText(input.ruleName(), "规则名不能为空");
    String reasonCode =
        notBlank(input.reasonCode()) ? input.reasonCode().trim().toUpperCase(Locale.ROOT) : "ANY";
    if (!REASON_CODES.contains(reasonCode)) {
      throw bad("调整单类型只能是 " + String.join(" / ", REASON_CODES) + "。");
    }
    String currency = requireCurrency(input.currency());
    BigDecimal minAmount = amount(input.minAmount(), "0");
    BigDecimal maxAmount = input.maxAmount() == null ? null : amount(input.maxAmount(), "0");
    if (maxAmount != null && maxAmount.compareTo(minAmount) <= 0) {
      throw bad("金额区间必须满足 下限 < 上限（上限留空 = 无上限）。");
    }
    BigDecimal minQty = amount(input.minQty(), "0");
    BigDecimal maxQty = input.maxQty() == null ? null : amount(input.maxQty(), "0");
    if (maxQty != null && maxQty.compareTo(minQty) <= 0) {
      throw bad("数量区间必须满足 下限 < 上限（上限留空 = 无上限）。");
    }
    String tier = requireTier(input.requiredTier());
    int approvals = input.requiredApprovals() == null ? 1 : input.requiredApprovals();
    if (approvals < 1 || approvals > 5) {
      throw bad("审批人数只能在 1~5 之间。");
    }
    int priority = input.priority() == null ? 100 : input.priority();
    return new NormalizedRule(
        ruleNo,
        ruleName,
        reasonCode,
        currency,
        minAmount,
        maxAmount,
        minQty,
        maxQty,
        tier,
        approvals,
        priority,
        optionalText(input.note(), 2000));
  }

  private String requireCurrency(String raw) {
    String v = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if ("ANY".equals(v)) {
      return "ANY";
    }
    if (!v.matches("[A-Z]{3}")) {
      throw bad("币种必须是三个大写字母（例：USD / CNY），或 ANY 表示不限币种。");
    }
    return v;
  }

  private String requireTier(String raw) {
    String v = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if (!TIERS.contains(v)) {
      throw bad("审批层级只能是 " + String.join(" / ", TIERS) + "。");
    }
    return v;
  }

  private String requireStatus(String raw) {
    String v = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if (!"ACTIVE".equals(v) && !"INACTIVE".equals(v)) {
      throw bad("状态只能是 ACTIVE 或 INACTIVE。");
    }
    return v;
  }

  /** 金额归一化：4 位小数，**不做静默四舍五入**（被悄悄改掉的数字会让人以为记的是那个数）。 */
  private static BigDecimal amount(BigDecimal raw, String fallback) {
    if (raw == null) {
      return new BigDecimal(fallback);
    }
    try {
      return raw.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw bad(
          "金额最多 4 位小数（numeric(19,4)）："
              + raw.toPlainString()
              + "。系统不会替你四舍五入 —— 被悄悄改掉的数字会让人以为记的是那个数。");
    }
  }

  private String nextCaseNo(OffsetDateTime now) {
    return "ADJ-APP-"
        + CASE_DAY.format(now)
        + "-"
        + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t")
        .get("t", UUID.class);
  }

  private static BigDecimal scale(BigDecimal v) {
    return v == null ? ZERO : v.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  private static String nullToEmpty(String v) {
    return v == null ? "" : v;
  }

  private static String optionalText(String raw, int max) {
    String v = raw == null ? "" : raw.trim();
    if (v.length() > max) {
      throw bad("文本长度不能超过 " + max + " 个字符。");
    }
    return v;
  }

  private static String requireText(String raw, String message) {
    String v = raw == null ? "" : raw.trim();
    if (v.isEmpty()) {
      throw bad(message);
    }
    return v;
  }

  private static UUID oSubmittedBy(Record r) {
    return r.get("submitted_by", UUID.class);
  }

  /**
   * 回滚到保存点。
   *
   * <p>⚠️ **回滚失败不吞掉**：那一张单的半截写入可能留在事务里 ——
   * 让整批失败，比提交一条不一致的审批留痕安全（照抄 6.3.1.3 的同一条纪律）。
   */
  private void rollbackTo(String name) {
    try {
      sql.execute(sql.rollback().toSavepoint(name));
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "批量审批里有一张单回滚失败，整批作废：" + e.getMessage(), e);
    }
  }

  /** 释放保存点（失败也不该影响业务结果）。 */
  private void releaseQuietly(String name) {
    try {
      sql.execute(sql.releaseSavepoint(name));
    } catch (RuntimeException ignored) {
      // 释放失败无害：事务提交 / 回滚时 PostgreSQL 会自己清掉它。
    }
  }

  /** 数据库口径的当前时刻（timestamptz ⇒ OffsetDateTime，不会有时区偏移）。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select now()").get(0, OffsetDateTime.class);
  }

  private static String failureMessage(RuntimeException e) {
    if (e instanceof ResponseStatusException rse) {
      return rse.getReason() == null ? "处理失败" : rse.getReason();
    }
    String message = e.getMessage();
    return message == null || message.isBlank() ? "处理失败" : message;
  }

  private static ResponseStatusException notFound(String what, UUID id) {
    return new ResponseStatusException(
        org.springframework.http.HttpStatus.NOT_FOUND, what + " " + id + " 不存在或不属于当前租户");
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, message);
  }
}
