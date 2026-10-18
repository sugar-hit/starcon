package com.starcon.trade;

import static com.starcon.trade.RefundApplicationModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 11.3.1.1 退款申请。
 *
 * <p><b>本类是 V166 三张新表的写入所有者</b>（refund_approval_policy / refund_application /
 * refund_application_event），对 V156 的 aftersales_case 簇、V161 的 rma_authorization 簇与
 * V159 的 refund_execution 簇<b>只读</b>。刻意不加外键、不 ALTER 既有表 ⇒ 跨车道 DDL 回归面 = 0。
 *
 * <p><b>六条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       RLS 是最后一道（FORCE 到 owner），查询本身也带条件，两层独立生效。
 *       本模块尤其不能省：申请单带着<b>原收款流水号</b>与<b>平台退款单号</b>，
 *       而审批 / 派发 / 登记结果都是<b>按 id 直达</b>的 URL。</li>
 *   <li><b>可退余额在服务端算，不信前端传来的数</b>（{@link #balance}）。
 *       五个占用桶与服务层各查一次，DB 触发器 {@code guard_refund_application} <b>再算一遍</b> ——
 *       绕过服务层直连 SQL 也绕不过（V166 第二节的【闸二】）。</li>
 *   <li><b>建单 / 提交都要重新校验</b>：页面打开到点「提交」之间可能已经过了几十分钟、
 *       别的单子又批走了一部分。可退余额是<b>提交那一刻</b>的事实，不是页面打开那一刻的。</li>
 *   <li><b>审批规则默认 fail-closed</b>（{@link #policy}）：没有配置时一律要求双人签。
 *       资损模块的默认值必须是拦住而不是放过。</li>
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而审批留痕的时刻错了会让事后审计读不出「谁先谁后」。</li>
 *   <li><b>状态流转只有一个入口</b>（{@link #transition}）：每次改状态都过版本校验，
 *       都写留痕。服务层不暴露裸改状态的端点。</li>
 * </ol>
 */
@Service
public class RefundApplicationService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;

  public RefundApplicationService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的五个区块逐块报可用性，下游逐个报 Delivered=false 与归属模块号。
   *
   * <p>让界面能说清「为什么这张单还没有执行结果」「为什么只能双签」，
   * 而不是让它们显示成空白让人以为「这些能力没做」。
   */
  public RefundCapabilities capabilities() {
    return new RefundCapabilities(
        List.of(
            "退款申请 refund_application（V166，本模块写入所有者）",
            "退款方式 refund_method（三类，与 V159 platform_refund_method 逐字同构）",
            "可退余额校验 服务端实时查 + DB 触发器 guard_refund_application 再算一遍（V166 第二节）",
            "审批规则 refund_approval_policy（V166，**租户可维护**；无配置时 fail-closed）",
            "审批留痕 refund_application_event（追加式，DB 触发器按状态变更自动追加）",
            "上游锚点 aftersales_case（V156）· rma_authorization（V161）· 同库只读"),
        List.of(
            section("refundMethod", "退款方式（原路/平台/余额）", true, null, null,
                "refund_application.refund_method"),
            section("partialAmount", "金额可部分退", true, null, null,
                "refund_application.refund_amount（numeric(19,4)，禁浮点）"),
            section("reason", "退款原因", true, null, null,
                "refund_application.refund_reason（五类内联词表）+ refund_reason_note"),
            section("refundableBalance", "可退余额校验（已退 + 在途）", true, null,
                "**服务端强制**：发起时算一次、提交时再算一次、DB 触发器写入时刻再算一次。"
                    + "五个占用桶逐桶单列：已退 / 在途派发 / 已登记待发起 / 待审占用 / 对账争议。"
                    + "蓝图加粗句「可退余额校验必须在服务端做」——重复提交退款是资损事故的高发点。",
                "RefundBalance（GET /trade/refund-applications/refundable-balance?caseId=）"),
            section("approvalRules", "审批规则", true, null,
                "**没有配置时 fail-closed**：查不到 ACTIVE 规则 ⇒ 一律要求双人签。"
                    + "默认值必须是拦住而不是放过（资损模块）。"
                    + "双人签的两条硬规则由 DB CHECK 钉死：复审人 ≠ 初审人、初审人 ≠ 提交人。",
                "refund_approval_policy（V166，租户可维护）"),
            section("handoffToFinance", "交财务执行", false, "12.2.2.2 付款执行",
                "蓝图关系句的下游是 12.2.2.2 付款执行（退款专用业务类型，保留原收款关联），"
                    + "而 **12 财务整卷未交付** —— backend/services/ 下没有 finance 服务实现。"
                    + "⇒ 本模块只做到「已派发并挂上执行记录」为止；真实付款动作与原收款关联的保留"
                    + "当前**没有人做**，capabilities 如实报出，绝不假装已对接。",
                null),
            section("refundExecutionState", "退款执行状态与查证", false, "11.3.1.2 退款状态",
                "派发 / 查证交易 / 重试 / 平台同步 / 对账差异属 11.3.1.2（**已交付**，V159）。"
                    + "本模块**只读**它的执行状态用于回显，**不写**那张表（事实表只有一个写入所有者）。"
                    + "「外部退款超时不等于失败」那条纪律由它承担：执行记录停在 UNKNOWN 时，"
                    + "本模块的申请单停在「执行中」而**不会**变成失败。",
                null),
            section("orderRefundStatus", "订单退款状态回写", false, "9 订单",
                "蓝图关系句里 11.3.1.1 的下游是 12 财务，订单退款状态回写属 9 订单卷，"
                    + "当前该对接不存在。本模块**不建影子列**。",
                null)),
        relatedModules(),
        List.of(
            new RefundBoundary("发起与审批之外的执行动作（付款、查证、重试、对账）", "11.3.1.2 退款状态",
                "派发前的查证、失败重试、平台同步与差异裁定是 11.3.1.2 的地盘（已交付）。"
                    + "本模块刻意不代写它那张表 —— 代写会让「这笔钱退没退出去」出现两个来源。"),
            new RefundBoundary("真实付款执行与原收款关联", "12.2.2.2 付款执行",
                "12 财务整卷未交付。本模块的 EXECUTING 只说明「已交出去、还没回结果」，"
                    + "**不代表钱已经退了**。"),
            new RefundBoundary("退货数量与实物处置", "11.2.1.1 退货授权 / 11.2.2.x",
                "「这次退的是哪几件」是退货授权行的职责（authorized_qty），"
                    + "本模块只回答「退多少钱、怎么退、批没批」。两件事混在一张单上，"
                    + "退款金额会与授权行数量对不上。"),
            new RefundBoundary("改写售后单状态", "11.1.1.3 售后受理",
                "aftersales_case.status 的流转属 11.1.1.3，而 aftersales_case 是 11.1.1.1 的写入所有者。"
                    + "本模块**只读**它，不代改 —— 代改会让「谁受理了这张单」出现两个答案。")),
        "一个状态列到底：DRAFT → PENDING_APPROVAL →（单签 APPROVED / 双签 "
            + "SECOND_APPROVAL_PENDING → SECOND_APPROVED）→ EXECUTING → SUCCEEDED | FAILED，"
            + "外加 REJECTED 与 CANCELLED 两个终态。**没有「超时就当失败」这条自动边**："
            + "外部超时不等于失败（11.3.1.2 已在 V159 用 UNKNOWN 态实现，本模块继承）。",
        "可退余额 = 售后单申请金额 − 已退 − 在途，五个占用桶全部**服务端实时查**："
            + "已退与在途派发 / 已登记待发起 / 对账争议取自 V159 refund_execution，"
            + "待审占用与人工登记成功取自本表。**FAILED 不占用**（V159 已钉死「失败 = 钱确定没有出去」，"
            + "把它算进占用会让一张明确失败的退款永久吃掉额度）。"
            + "同一套算法在 DB 触发器里**再算一遍**，绕过服务层直连 SQL 也绕不过。",
        "租户可维护的退款审批规则：金额 **≥** 双人签阈值 ⇒ 必须两人点头。"
            + "**没有配置时一律双人签**（fail-closed）。阈值取的是**提交那一刻**的口径，"
            + "提交之后再改规则不会让一张待审的单凭空多出或少掉一次审批。",
        "交财务执行（EXECUTING）只说明「已派发并挂上 11.3.1.2 的执行记录」，"
            + "**不代表钱已经退了**：真实付款执行属 12.2.2.2，12 财务整卷未交付，capabilities 如实报出。");
  }

  private static RefundSectionCapability section(
      String section, String label, boolean available, String ownerModule, String reason, String landsIn) {
    return new RefundSectionCapability(section, label, available, ownerModule, reason, landsIn);
  }

  /** 关联模块可用性。逐条报 Delivered 与归属，不让界面自己猜。 */
  private List<RefundRelatedModule> relatedModules() {
    return List.of(
        new RefundRelatedModule("AFTERSALES_CASE", "售后单详情", true, null,
            "**已交付**（11.1.1.2，V156/V157，同库只读）：提供可退基数的真相"
                + "（requested_amount）与这笔退款的业务锚点。", null),
        new RefundRelatedModule("RMA_AUTHORIZATION", "退货授权单", true, null,
            "**已交付**（11.2.1.1，V161，同库只读）：作为「这笔退款来自哪张退货授权」的溯源锚点。"
                + "本模块**不写**它 —— 退货的实物处置是 11.2.2.x 的写入所有权。", null),
        new RefundRelatedModule("REFUND_EXECUTION", "退款执行状态与查证", true, null,
            "**已交付**（11.3.1.2，V159，同库只读）：提供已退 / 在途的占用事实，"
                + "并承担「先查证交易再重试」「超时不等于失败」两条纪律。", null),
        new RefundRelatedModule("FINANCE_PAYMENT", "付款执行（退款专用业务类型）", false, "12.2.2.2",
            "12 财务整卷**未交付** —— backend/services/ 下没有 finance 服务实现。"
                + "蓝图关系句点名它「保留原收款关联」，而当前**没有任何模块做这件事**。", null));
  }

  // ================================================================ 审批规则

  /**
   * 读一条币种的审批规则。
   *
   * <p>⚠️ <b>查不到 ACTIVE 规则时 {@code requiresDualApproval} 恒为 true</b>（fail-closed），
   * 且 {@code policySource=DEFAULT_NO_POLICY} 如实报出 ——
   * 界面必须能显示「本租户还没配规则，当前按最严格档执行」，而不是显示一个编造的阈值。
   */
  public RefundPolicyView policy(String currency, BigDecimal amount) {
    String ccy = requireCurrency(currency);
    Record row = sql.fetchOne(
        "select dual_approval_threshold, note, effective_from, updated_by, updated_at"
            + " from refund_approval_policy"
            + " where tenant_id = ?::uuid and currency = ?::char(3) and policy_status = 'ACTIVE'",
        currentTenant(), ccy);
    if (row == null) {
      return new RefundPolicyView(ccy, "MISSING", BigDecimal.ZERO, true, "DEFAULT_NO_POLICY",
          "本租户还没有为 " + ccy + " 配置退款审批规则。**未配置 = 最严格档**："
              + "任何金额的退款都必须两人点头（初审人 ≠ 提交人，复审人 ≠ 初审人）。",
          null, null, null);
    }
    BigDecimal threshold = row.get("dual_approval_threshold", BigDecimal.class);
    // ⚠️ **双人签与否取决于金额，不能恒为 true**：
    //   规则本身只给阈值，「这笔要不要第二个人」是阈值与本笔金额的比较结果。
    //   返回一个恒 true 的布尔会让运营以为「配了规则也没用」，从而干脆不配。
    //   amount 传 null（界面上还没填金额时问规则）按最严格档回答。
    boolean requiresDual =
        amount == null || amount.compareTo(threshold) >= 0;
    return new RefundPolicyView(ccy, "ACTIVE", threshold,
        requiresDual, "CONFIGURED", nullable(row.get("note", String.class)),
        row.get("effective_from", OffsetDateTime.class), row.get("updated_by", UUID.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 维护审批规则（同币种已有 ACTIVE 行则原地改，否则新建）。 */
  public RefundPolicyView upsertPolicy(RefundPolicyUpsert request, UUID actor) {
    String ccy = requireCurrency(request.currency());
    if (request.dualApprovalThreshold() == null || request.dualApprovalThreshold().signum() < 0) {
      throw new IllegalArgumentException("双人签阈值不能为空且不能为负（0 表示任何金额都要两人签）");
    }
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime effectiveFrom =
        request.effectiveFrom() == null ? at : request.effectiveFrom();
    String note = request.note() == null ? "" : request.note().trim();
    if (note.length() > 1000) {
      throw new IllegalArgumentException("规则说明不能超过 1000 个字符");
    }
    UUID tenant = currentTenant();
    BigDecimal threshold = request.dualApprovalThreshold().setScale(4, java.math.RoundingMode.HALF_UP);

    // ⚠️ upsert 走显式 SELECT + INSERT/UPDATE，不用 ON CONFLICT：
    //   部分唯一索引（WHERE policy_status='ACTIVE'）无法被 ON CONFLICT 的推断覆盖，
    //   而 ON CONFLICT DO NOTHING 会在「已有 DISABLED 行」时静默什么都不做 ——
    //   运营改了规则却发现没生效，而界面上一点报错都没有。
    Record existing =
        sql.fetchOne(
            "select id from refund_approval_policy"
                + " where tenant_id = ?::uuid and currency = ?::char(3)"
                + " and policy_status = 'ACTIVE'",
            tenant, ccy);

    UUID existingId = existing == null ? null : existing.get("id", UUID.class);

    if (existingId == null) {
      sql.execute(
          "insert into refund_approval_policy(id,tenant_id,currency,policy_status,"
              + "dual_approval_threshold,effective_from,note,created_by,created_at,updated_by,updated_at)"
              + " values(?::uuid,?::uuid,?::char(3),'ACTIVE',?::numeric,?::timestamptz,?,"
              + "?::uuid,?::timestamptz,?::uuid,?::timestamptz)",
          UUID.randomUUID(), tenant, ccy, threshold, effectiveFrom, note, actor, at, actor, at);
    } else {
      sql.execute(
          "update refund_approval_policy set dual_approval_threshold = ?::numeric,"
              + " effective_from = ?::timestamptz, note = ?, updated_by = ?::uuid, updated_at = ?::timestamptz"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          threshold, effectiveFrom, note, actor, at, tenant, existingId);
    }
    return policy(ccy, null);
  }

  // ================================================================ 可退余额校验

  /**
   * 可退余额校验（蓝图加粗句「可退余额校验必须在服务端做」的直接落点）。
   *
   * <p>五个占用桶与 V166 的 {@code guard_refund_application} <b>逐桶同构</b>：
   *
   * <ul>
   *   <li><b>已退</b> settled    = Σ 执行记录[SUCCEEDED]</li>
   *   <li><b>在途派发</b> dispatched = Σ 执行记录[IN_FLIGHT, UNKNOWN]</li>
   *   <li><b>已登记待发起</b> registered = Σ 执行记录[NOT_INITIATED]</li>
   *   <li><b>待审占用</b> pending = Σ 本表[待审 / 已批 / 执行中 且未挂执行记录]</li>
   *   <li><b>对账争议</b> disputed = Σ 执行记录[DISCREPANCY]</li>
   * </ul>
   *
   * <p>⚠️ <b>「未挂执行记录」这个限定条件是防双算的</b>：挂了 execution_id 的申请单，
   * 额度改由那条执行记录的桶计一次 —— 否则同一笔钱会被本表与执行记录各算一遍。
   */
  public RefundBalance balance(UUID caseId) {
    if (caseId == null) {
      throw new IllegalArgumentException("必须指定售后单（caseId）");
    }
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    UUID tenant = currentTenant();

    Record head =
        sql.fetchOne(
            "select c.id, c.case_no, c.case_type, c.status, c.order_id, c.currency,"
                + " c.requested_amount, o.order_no"
                + " from aftersales_case c"
                + " left join sales_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                + " where c.tenant_id = ?::uuid and c.id = ?::uuid",
            tenant, caseId);
    if (head == null) {
      // 跨租户的 id 与不存在的 id 同一条消息：不告诉调用方「这个 ID 存在但不属于你」。
      throw new IllegalArgumentException("售后单不存在或不属于当前租户：" + caseId);
    }

    String caseStatus = head.get("status", String.class);
    BigDecimal base = nz(head.get("requested_amount", BigDecimal.class));

    // 已退**只**来自执行记录：表级 CHECK 规定「派发过的申请必有 execution_id」，
    //   所以「已成功但未挂执行记录」这条路径根本走不到 —— 写一个恒为 0 的桶
    //   只会让读代码的人以为存在一条人工旁路。
    BigDecimal settled = sumExecutions(tenant, head, "execution_state = 'SUCCEEDED'");
    BigDecimal dispatched = sumExecutions(tenant, head, "execution_state in ('IN_FLIGHT','UNKNOWN')");
    BigDecimal registered = sumExecutions(tenant, head, "execution_state = 'NOT_INITIATED'");
    BigDecimal disputed = sumExecutions(tenant, head, "execution_state = 'DISCREPANCY'");
    BigDecimal pending =
        sumApplicationBalances(
            tenant,
            caseId,
            "application_status in ('PENDING_APPROVAL','APPROVED',"
                + "'SECOND_APPROVAL_PENDING','SECOND_APPROVED','EXECUTING')");

    BigDecimal occupied = settled.add(dispatched).add(registered).add(pending).add(disputed);
    // ⚠️ **不截断成 0**：「超了 50 块」与「正好退光了」在界面上必须分得开。
    BigDecimal available = base.subtract(occupied);

    List<String> blocking = new ArrayList<>();
    if (List.of("RESOLVED", "REJECTED", "CLOSED").contains(caseStatus)) {
      blocking.add("售后单已结案（" + caseStatus + "），不能再发起退款申请");
    }
    if (base.signum() <= 0) {
      blocking.add("该售后单的申请金额为 0，没有可退基数");
    }
    if (available.signum() <= 0) {
      blocking.add("可退余额已被占满（基数 " + base.stripTrailingZeros()
          + "，已退 " + settled.stripTrailingZeros()
          + "，在途派发 " + dispatched.stripTrailingZeros()
          + "，已登记待发起 " + registered.stripTrailingZeros()
          + "，待审占用 " + pending.stripTrailingZeros()
          + "，对账争议 " + disputed.stripTrailingZeros() + "）");
    }
    if (disputed.signum() > 0) {
      blocking.add("存在对账争议金额 " + disputed.stripTrailingZeros()
          + "（11.3.1.2）：平台与系统对不上，按**宁可拦住也不能放过**计入占用");
    }

    return new RefundBalance(
        head.get("id", UUID.class),
        head.get("case_no", String.class),
        head.get("case_type", String.class),
        caseStatus,
        head.get("order_id", UUID.class),
        nullable(head.get("order_no", String.class)),
        head.get("currency", String.class),
        base,
        settled,
        dispatched,
        registered,
        pending,
        disputed,
        occupied,
        available,
        blocking.isEmpty(),
        blocking,
        "可退基数 aftersales_case.requested_amount（11.1.1.1 / V156，同库只读）；"
            + "占用桶 ①②④⑤ 取自 V159 refund_execution（11.3.1.2，同库只读），"
            + "桶 ③ 取自本表。**FAILED 不占用**：V159 已钉死「失败 = 钱确定没有出去」。",
        at);
  }

  /** 某个桶：执行记录侧。恒为空的执行记录按**同一张订单**计入（钱是从同一笔买家付款里出的）。 */
  private BigDecimal sumExecutions(UUID tenant, Record caseHead, String stateCondition) {
    return sql.fetchOne(
            "select coalesce(sum(e.refund_amount), 0)"
                + " from refund_execution e"
                + " where e.tenant_id = ?::uuid and e.order_id = ?::uuid"
                + "   and (e.case_id = ?::uuid or e.case_id is null)"
                + "   and " + stateCondition,
            tenant,
            caseHead.get("order_id", UUID.class),
            caseHead.get("id", UUID.class))
        .get(0, BigDecimal.class);
  }

  /** 某个桶：本表侧。<b>只算未挂执行记录的</b> —— 挂了 execution_id 的由执行记录的桶计一次。 */
  private BigDecimal sumApplicationBalances(UUID tenant, UUID caseId, String statusCondition) {
    return sql.fetchOne(
            "select coalesce(sum(a.refund_amount), 0)"
                + " from refund_application a"
                + " where a.tenant_id = ?::uuid and a.case_id = ?::uuid"
                + "   and a.execution_id is null"
                + "   and " + statusCondition,
            tenant, caseId)
        .get(0, BigDecimal.class);
  }

  // ================================================================ 列表

  /** 列表：真分页（LIMIT/OFFSET + 独立 COUNT）+ 全量汇总 + 多维筛选 + 排序白名单。 */
  public RefundPage list(RefundListQuery query) {
    int page = Math.max(0, query.page() == null ? 0 : query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    RefundSort sort = RefundSort.parse(query.sort());
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    UUID tenant = currentTenant();

    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    // 左连售后单 / 退货授权单 / 订单：编号要显示，而这三张表若因故缺失**这张申请仍要显示** ——
    // 「列表里没有这张单」在退款场景里比「单号缺失」严重得多。
    String fromBase = " from refund_application r"
        + " left join aftersales_case c on c.tenant_id = r.tenant_id and c.id = r.case_id"
        + " left join rma_authorization rm on rm.tenant_id = r.tenant_id and rm.id = r.rma_id"
        + " left join sales_order o on o.tenant_id = r.tenant_id and o.id = r.order_id";

    long total = sql.fetchOne("select count(*)" + fromBase + clause, binds.toArray())
        .get(0, Long.class);

    // ⚠️ 逐行可退余额用 **lateral** 而不是 SELECT 列表里的相关子查询：
    //   相关子查询在 SELECT 列表里每行求值一次且 PostgreSQL 不允许它带租户上下文绑定，
    //   而 lateral 一次算完、形状与 V161 的 w/w1 lateral 同一写法。
    //   ⚠️ lateral **只加在分页查询上**，COUNT 查询不加 —— 加了会把 binds 的顺序打乱。
    String fromPaged = fromBase + " left join lateral ("
        + "   select c.requested_amount"
        + "     - coalesce((select sum(e.refund_amount) from refund_execution e"
        + "          where e.tenant_id = r.tenant_id and e.order_id = r.order_id"
        + "            and (e.case_id = r.case_id or e.case_id is null)"
        + "            and e.execution_state <> 'FAILED'), 0)"
        + "     - coalesce((select sum(a.refund_amount) from refund_application a"
        + "          where a.tenant_id = r.tenant_id and a.case_id = r.case_id and a.id <> r.id"
        + "            and a.execution_id is null"
        + "            and a.application_status in ('PENDING_APPROVAL','APPROVED',"
        + "              'SECOND_APPROVAL_PENDING','SECOND_APPROVED','EXECUTING','SUCCEEDED')), 0)"
        + "     as refundable_amount"
        + "    from aftersales_case c"
        + "   where c.tenant_id = r.tenant_id and c.id = r.case_id) w on true";

    List<Object> pageBinds = new ArrayList<>();
    pageBinds.add(at);
    pageBinds.add(at);
    pageBinds.addAll(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);

    List<RefundRow> rows =
        sql.fetch(
                "select r.*, c.case_no, c.case_type, c.status as case_status, c.requested_amount,"
                    + " rm.rma_no, o.order_no, w.refundable_amount," + timelinessCase()
                    + fromPaged + clause
                    + " order by " + sort.sql()
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(r -> row(r, tenant));

    return new RefundPage(
        rows, page, size, total, summary(where, binds), at,
        "真分页（LIMIT/OFFSET + 独立 COUNT），汇总与排序不受分页影响。",
        "待审工作面 = PENDING_APPROVAL + SECOND_APPROVAL_PENDING。"
            + "⚠️ **没有规则时一律双人签**（fail-closed），所以「待审」天然分两段。",
        "行里的 refundableAmount 是**这一张单还能退多少**（基数 − 本单之后的全部占用），"
            + "不是一个静态列 —— 同一张单在不同时间刷出来可能不同，这正是它在变的证据。",
        "排序白名单四个键（created_desc / amount_desc / status_asc / submitted_asc）；"
            + "其余取值落回 created_desc，不接受任意表达式。");
  }

  /**
   * 执行时效的两个投影列（状态 + 剩余秒数），SQL 文本形状固定。
   *
   * <p>⚠️ 占位符恰好两个，调用方必须按顺序先绑两个参考时刻，再绑 where 里的值。
   */
  private static String timelinessCase() {
    return " case when r.application_status <> 'EXECUTING' then 'NOT_APPLICABLE'"
        + "      when r.dispatched_at is null then 'NOT_APPLICABLE'"
        + "      when r.dispatched_at < ?::timestamptz - interval '24 hours' then 'OVERDUE'"
        + "      else 'IN_FLIGHT' end as timeliness_state,"
        + " case when r.application_status = 'EXECUTING'"
        + "      then extract(epoch from (?::timestamptz - r.dispatched_at))::bigint"
        + "      else null end as remaining_seconds";
  }

  private List<String> conditions(RefundListQuery query, List<Object> binds) {
    List<String> where = new ArrayList<>();
    where.add("r.tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (isSet(query.status())) {
      RefundApplicationStatus status = parseEnum(RefundApplicationStatus.class, query.status(), "申请状态");
      where.add("r.application_status = ?");
      binds.add(status.name());
    }
    if (isSet(query.method())) {
      where.add("r.refund_method = ?");
      binds.add(parseEnum(RefundMethod.class, query.method(), "退款方式").name());
    }
    if (isSet(query.reason())) {
      where.add("r.refund_reason = ?");
      binds.add(parseEnum(RefundReason.class, query.reason(), "退款原因").name());
    }
    if (query.caseId() != null) {
      where.add("r.case_id = ?::uuid");
      binds.add(query.caseId());
    }
    if (Boolean.TRUE.equals(query.pendingReviewOnly())) {
      where.add("r.application_status in ('PENDING_APPROVAL','SECOND_APPROVAL_PENDING')");
    }
    if (isSet(query.keyword())) {
      // 真实模糊检索：申请单号 / 售后单号 / 退货授权单号 / 订单号 / 原收款流水号都命中。
      where.add("(r.application_no ilike ? or c.case_no ilike ? or rm.rma_no ilike ?"
          + " or o.order_no ilike ? or r.original_payment_ref ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 5; i++) {
        binds.add(like);
      }
    }
    return where;
  }

  private RefundSummary summary(List<String> where, List<Object> binds) {
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    Record row =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where r.application_status = 'DRAFT') as draft,"
                + " count(*) filter (where r.application_status = 'PENDING_APPROVAL') as pending,"
                + " count(*) filter (where r.application_status = 'SECOND_APPROVAL_PENDING') as second_pending,"
                + " count(*) filter (where r.application_status in ('APPROVED','SECOND_APPROVED')) as approved,"
                + " count(*) filter (where r.application_status = 'EXECUTING') as executing,"
                + " count(*) filter (where r.application_status = 'SUCCEEDED') as succeeded,"
                + " count(*) filter (where r.application_status = 'FAILED') as failed,"
                + " count(*) filter (where r.application_status = 'REJECTED') as rejected,"
                + " count(*) filter (where r.application_status = 'CANCELLED') as cancelled,"
                + " coalesce(sum(r.refund_amount) filter (where r.application_status in"
                + "   ('PENDING_APPROVAL','SECOND_APPROVAL_PENDING')), 0) as pending_amount,"
                + " coalesce(sum(r.refund_amount) filter (where r.application_status = 'EXECUTING'), 0)"
                + "   as executing_amount"
                + " from refund_application r"
                + " left join aftersales_case c on c.tenant_id = r.tenant_id and c.id = r.case_id"
                + " left join rma_authorization rm on rm.tenant_id = r.tenant_id and rm.id = r.rma_id"
                + " left join sales_order o on o.tenant_id = r.tenant_id and o.id = r.order_id"
                + clause,
            binds.toArray());
    return new RefundSummary(
        row.get("total", Long.class), row.get("draft", Long.class), row.get("pending", Long.class),
        row.get("second_pending", Long.class), row.get("approved", Long.class),
        row.get("executing", Long.class), row.get("succeeded", Long.class),
        row.get("failed", Long.class), row.get("rejected", Long.class),
        row.get("cancelled", Long.class), row.get("pending_amount", BigDecimal.class),
        row.get("executing_amount", BigDecimal.class));
  }

  /** 筛选项词表：来自**真实数据**的 DISTINCT + 计数，不是写死的常量数组。 */
  public RefundApplicationFilterOptions filters() {
    UUID tenant = currentTenant();
    List<FilterOption> statuses = distinctCounts(
        tenant,
        "select application_status as v, count(*) as n from refund_application"
            + " where tenant_id = ?::uuid group by application_status",
        RefundApplicationStatus.class);
    List<FilterOption> methods = distinctCounts(
        tenant,
        "select refund_method as v, count(*) as n from refund_application"
            + " where tenant_id = ?::uuid group by refund_method",
        RefundMethod.class);
    List<FilterOption> reasons = distinctCounts(
        tenant,
        "select refund_reason as v, count(*) as n from refund_application"
            + " where tenant_id = ?::uuid group by refund_reason",
        RefundReason.class);
    List<FilterOption> currencies = new ArrayList<>();
    for (Record r : sql.fetch(
        "select currency as v, count(*) as n from refund_application"
            + " where tenant_id = ?::uuid group by currency order by currency",
        tenant)) {
      currencies.add(new FilterOption(r.get("v", String.class), null, r.get("n", Long.class)));
    }
    return new RefundApplicationFilterOptions(
        statuses.stream().map(FilterOption::value).toList(),
        methods.stream().map(FilterOption::value).toList(),
        reasons.stream().map(FilterOption::value).toList(),
        currencies,
        statuses.stream().mapToLong(FilterOption::count).sum());
  }

  private <E extends Enum<E> & RefundApplicationModels.Labelled> List<FilterOption> distinctCounts(
      UUID tenant, String raw, Class<E> type) {
    List<FilterOption> out = new ArrayList<>();
    for (Record r : sql.fetch(raw + " order by v", tenant)) {
      String rawValue = r.get("v", String.class);
      E parsed = parseEnum(type, rawValue, "词表");
      out.add(new FilterOption(parsed.name(), parsed.label(), r.get("n", Long.class)));
    }
    return out;
  }

  // ================================================================ 详情

  /** 单张退款申请单全景：头 + 可退余额 + 审批规则 + 执行记录状态 + 留痕 + 可执行动作。 */
  public RefundDetail detail(UUID id) {
    Record r = head(id);
    UUID tenant = currentTenant();
    UUID caseId = r.get("case_id", UUID.class);
    RefundHead head = head(r);

    RefundBalance balance = balance(caseId);
    // ⚠️ 行里的 refundableAmount 要把**本单自己的金额**加回可退余额 ——
    //   balance() 把本单算进了占用里（它还没退回额度），不减掉就会把「这张单还能退多少」
    //   显示成 0，界面上就像「已经占满」而其实还空着。
    BigDecimal refundable = balance.availableAmount().add(head.refundAmount()).max(BigDecimal.ZERO);

    String executionState = null;
    String externalRef = null;
    String executionNo = null;
    if (head.executionId() != null) {
      Record exec = sql.fetchOne(
          "select execution_state, external_transaction_ref, refund_no"
              + " from refund_execution where tenant_id = ?::uuid and id = ?::uuid",
          tenant, head.executionId());
      if (exec != null) {
        executionState = exec.get("execution_state", String.class);
        externalRef = nullable(exec.get("external_transaction_ref", String.class));
        executionNo = nullable(exec.get("refund_no", String.class));
      }
    }
    // ⚠️ 列表的 SELECT 没有 join refund_execution（那是 11.3.1.2 的表，且一对一取列会拖慢列表），
    //   所以执行单号只在详情里补上 —— 不补就是一个恒为 null 的死列，比 404 更能骗人。
    RefundHead detailed = withExecutionNo(head, executionNo);

    return new RefundDetail(
        detailed,
        withRefundable(balance, refundable),
        policy(head.currency(), head.refundAmount()),
        executionState,
        externalRef,
        executionNote(head, executionState),
        events(id),
        actions(head),
        capabilities().sections().stream()
            .filter(s -> "refundableBalance".equals(s.section()))
            .findFirst()
            .orElse(null),
        capabilities().sections().stream()
            .filter(s -> "handoffToFinance".equals(s.section()))
            .findFirst()
            .orElse(null));
  }

  /** 详情里补上执行单号（record 不可变，所以复制一份）。 */
  private static RefundHead withExecutionNo(RefundHead h, String executionNo) {
    return new RefundHead(
        h.id(), h.applicationNo(), h.caseId(), h.caseNo(), h.rmaId(), h.rmaNo(),
        h.orderId(), h.orderNo(), h.applicationStatus(), h.applicationStatusLabel(),
        h.refundMethod(), h.refundMethodLabel(), h.refundAmount(), h.currency(),
        h.refundReason(), h.refundReasonLabel(), h.refundReasonNote(),
        h.originalPaymentRef(), h.platformRefundRef(), h.submittedBy(), h.submittedAt(),
        h.firstApprovedBy(), h.firstApprovedAt(), h.secondApprovedBy(), h.secondApprovedAt(),
        h.approvalNote(), h.dispatchedBy(), h.dispatchedAt(), h.executionId(), executionNo,
        h.outcomeSource(), h.outcomeRecordedBy(), h.outcomeRecordedAt(), h.failureReason(),
        h.cancelReason(), h.createdBy(), h.createdAt(), h.updatedAt(), h.version());
  }

  /** 把行内算出来的可退余额塞回报告（{@link #balance} 只负责口径，逐行减法在这一层做）。 */  private RefundBalance withRefundable(RefundBalance balance, BigDecimal refundable) {
    return new RefundBalance(
        balance.caseId(), balance.caseNo(), balance.caseType(), balance.caseStatus(),
        balance.orderId(), balance.orderNo(), balance.currency(), balance.baseAmount(),
        balance.settledAmount(), balance.dispatchedAmount(), balance.registeredAmount(),
        balance.pendingAmount(), balance.disputedAmount(), balance.occupiedAmount(),
        refundable, refundable.signum() > 0 && balance.blockingReasons().stream()
            .noneMatch(r -> r.startsWith("售后单已结案") || r.startsWith("该售后单的申请金额")),
        balance.blockingReasons(), balance.balanceSource(), balance.evaluatedAt());
  }

  /**
   * 申请状态与执行状态的口径说明。
   *
   * <p>⚠️ 这一段是蓝图加粗句在传输层的落点：执行记录停在 {@code UNKNOWN} 时，
   * 申请单**停在执行中**而不是失败 —— 外部超时不等于失败，重复发起退款会造成实质资损。
   */
  private String executionNote(RefundHead head, String executionState) {
    if (head.executionId() == null) {
      return "本申请尚未派发：还没有挂上任何退款执行记录（11.3.1.2 / V159）。"
          + "派发时必须指明一条执行记录，本模块**不创建**它（事实表只有一个写入所有者）。";
    }
    if (executionState == null) {
      return "挂着的执行记录在当前租户下读不到 —— 这在 RLS 下只可能是被撤销或串租户，"
          + "已按「读不到」如实报出而不是当成不存在。";
    }
    return switch (executionState) {
      case "NOT_INITIATED" -> "执行记录已登记、**尚未对外派发**：钱还确定没有出去，占用额度（宁可拦住也不能放过）。";
      case "IN_FLIGHT" -> "执行记录已派发、等待外部结果：正常在途，本申请停在「执行中」。";
      case "UNKNOWN" -> "⚠️ **外部超时，结果未知** —— 这**不是失败**，本申请停在「执行中」而不会变成失败。"
          + "先查证交易再决定动作：查证确认没出去才允许重试，确认已出去只能推进到成功，"
          + "查不出来就保持未知（11.3.1.2 / V159 的查证闸）。重复发起退款会造成实质资损。";
      case "SUCCEEDED", "FAILED", "DISCREPANCY" ->
          "执行记录已定论（" + executionState + "），本模块**只读**它。"
              + "重试、查证与差异裁定属 11.3.1.2，本模块不代写那张表。";
      default -> "执行记录状态：" + executionState;
    };
  }

  /** 审批与状态留痕（追加式）。 */
  public List<RefundEvent> events(UUID applicationId) {
    return sql.fetch(
            "select id, event_type, from_status, to_status, amount_snapshot, currency_snapshot,"
                + " reason_note, occurred_by, occurred_at"
                + " from refund_application_event"
                + " where tenant_id = ?::uuid and application_id = ?::uuid"
                + " order by occurred_at, id",
            currentTenant(), applicationId)
        .map(r -> new RefundEvent(
            r.get("id", UUID.class), r.get("event_type", String.class),
            eventLabel(r.get("event_type", String.class)),
            nullable(r.get("from_status", String.class)), r.get("to_status", String.class),
            r.get("amount_snapshot", BigDecimal.class), r.get("currency_snapshot", String.class),
            nullable(r.get("reason_note", String.class)), r.get("occurred_by", UUID.class),
            r.get("occurred_at", OffsetDateTime.class)));
  }

  private static String eventLabel(String raw) {
    if (raw == null) {
      return "";
    }
    return switch (raw) {
      case "CREATED" -> "登记草稿";
      case "SUBMITTED" -> "提交审批";
      case "FIRST_APPROVED" -> "初审通过";
      case "SECOND_APPROVED" -> "复审通过";
      case "REJECTED" -> "审批驳回";
      case "DISPATCHED" -> "交财务执行";
      case "SUCCEEDED" -> "执行成功";
      case "FAILED" -> "执行失败";
      case "CANCELLED" -> "人工撤销";
      default -> "草稿要素变更";
    };
  }

  // ================================================================ 写

  /** 发起退款申请（草稿）。金额在这一刻就过一次可退余额校验。 */
  public RefundDetail create(RefundCreate request, UUID actor) {
    if (request == null) {
      throw new IllegalArgumentException("退款申请不能为空");
    }
    UUID tenant = currentTenant();
    RefundBalance balance = balance(request.caseId());

    RefundMethod method = parseEnum(RefundMethod.class, request.refundMethod(), "退款方式");
    RefundReason reason = parseEnum(RefundReason.class, request.refundReason(), "退款原因");
    BigDecimal amount = requirePositiveAmount(request.refundAmount());
    String currency = requireCurrency(request.currency());
    String reasonNote = request.refundReasonNote() == null ? "" : request.refundReasonNote().trim();
    String originalRef = blankToEmpty(request.originalPaymentRef());
    String platformRef = blankToEmpty(request.platformRefundRef());

    // 币种必须与售后单一致（跨币种退款会让可退余额的比失去意义；DB 触发器再拦一次）。
    if (!currency.equals(balance.currency())) {
      throw new IllegalArgumentException(
          "退款币种 " + currency + " 与售后单币种 " + balance.currency() + " 不一致："
              + "跨币种退款会让可退余额的比失去意义");
    }
    // ⚠️ **服务端校验**，不是信任前端传来的金额：这里也拦一次「金额 > 可退余额」。
    if (amount.compareTo(balance.availableAmount()) > 0) {
      throw new IllegalArgumentException("退款金额 " + amount.stripTrailingZeros()
          + " 超过可退余额 " + balance.availableAmount().stripTrailingZeros()
          + "（可退余额必须在服务端算，重复提交退款是资损事故的高发点）");
    }

    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    UUID id = UUID.randomUUID();
    String no = nextApplicationNo();

    sql.execute(
        "insert into refund_application(id,tenant_id,application_no,case_id,order_id,rma_id,"
            + "application_status,refund_method,refund_amount,currency,refund_reason,"
            + "refund_reason_note,original_payment_ref,platform_refund_ref,created_by,created_at,updated_at)"
            + " values(?::uuid,?::uuid,?,?::uuid,?::uuid,?::uuid,'DRAFT',?,?::numeric,?::char(3),?,?,?,?,"
            + "?::uuid,?::timestamptz,?::timestamptz)",
        id, tenant, no, request.caseId(), balance.orderId(), request.rmaId(),
        method.name(), amount, currency, reason.name(), reasonNote, originalRef, platformRef,
        actor, at, at);

    return detail(id);
  }

  /**
   * 改草稿要素。
   *
   * <p><b>只有 DRAFT 可改</b>：已提交的申请是一份待审的凭据，
   * 审批人看到的金额必须与他当时点「同意」的那个数字逐字相同。
   */
  public RefundDetail update(UUID id, RefundUpdate request) {
    Record r = head(id);
    RefundHead head = head(r);
    expectStatus(head, RefundApplicationStatus.DRAFT, "只有草稿可以修改");
    if (request == null) {
      throw new IllegalArgumentException("修改内容不能为空");
    }
    checkVersion(head, request.expectedVersion());

    RefundMethod method = parseEnum(RefundMethod.class, request.refundMethod(), "退款方式");
    RefundReason reason = parseEnum(RefundReason.class, request.refundReason(), "退款原因");
    BigDecimal amount = requirePositiveAmount(request.refundAmount());
    String currency = requireCurrency(request.currency());

    RefundBalance balance = balance(head.caseId());
    if (!currency.equals(balance.currency())) {
      throw new IllegalArgumentException("退款币种 " + currency + " 与售后单币种 "
          + balance.currency() + " 不一致");
    }
    if (amount.compareTo(balance.availableAmount()) > 0) {
      throw new IllegalArgumentException("退款金额 " + amount.stripTrailingZeros()
          + " 超过可退余额 " + balance.availableAmount().stripTrailingZeros());
    }

    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update refund_application set refund_method = ?, refund_amount = ?::numeric, currency = ?::char(3),"
            + " refund_reason = ?, refund_reason_note = ?, original_payment_ref = ?,"
            + " platform_refund_ref = ?, version = version + 1, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid and version = ?",
        method.name(), amount, currency, reason.name(),
        request.refundReasonNote() == null ? "" : request.refundReasonNote().trim(),
        blankToEmpty(request.originalPaymentRef()), blankToEmpty(request.platformRefundRef()),
        at, currentTenant(), id, head.version());
    return detail(id);
  }

  /**
   * 提交审批。
   *
   * <p>⚠️ <b>在提交这一刻重新校验可退余额</b>：页面打开到点「提交」之间可能过了几十分钟，
   * 别的单子又批走了一部分。建单时那次校验是<b>建单那一刻</b>的事实，不是提交那一刻的。
   */
  public RefundDetail submit(UUID id, RefundSubmit request, UUID actor) {
    Record r = head(id);
    RefundHead head = head(r);
    expectStatus(head, RefundApplicationStatus.DRAFT, "只有草稿可以提交审批");
    checkVersion(head, request == null ? null : request.expectedVersion());

    RefundBalance balance = balance(head.caseId());
    if (head.refundAmount().compareTo(balance.availableAmount()) > 0) {
      throw new IllegalArgumentException("提交时复核不通过：退款金额 "
          + head.refundAmount().stripTrailingZeros() + " 已超过当前可退余额 "
          + balance.availableAmount().stripTrailingZeros()
          + "（页面打开到提交之间，可退余额被别的单子占走了）");
    }

    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    // ⚠️ submitted_by 必须是**已认证的操作者**：留痕要回答「谁提的」，
    //   而「初审人 ≠ 提交人」这条硬规则全靠它成立 —— 写个随机 UUID 会让这条规则永远为真。
    transition(id, head.version(), "PENDING_APPROVAL",
        "submitted_by = ?::uuid, submitted_at = ?::timestamptz,",
        actor, at);
    return detail(id);
  }

  /**
   * 审批：同意 / 驳回。
   *
   * <p><b>落到 {@code APPROVED} 还是 {@code SECOND_APPROVAL_PENDING} 由审批规则决定</b>，
   * 调用方不能指定 —— 否则「要不要第二个人」就变成了请求体里的一个可绕过的布尔。
   * 驳回必须写理由；DB CHECK 与触发器再各兜一次。
   */
  public RefundDetail decide(UUID id, RefundDecision request, UUID actor) {
    if (request == null) {
      throw new IllegalArgumentException("审批内容不能为空");
    }
    Record r = head(id);
    RefundHead head = head(r);
    checkVersion(head, request.expectedVersion());
    String note = request.note() == null ? "" : request.note().trim();
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

    if (!request.approve()) {
      expectStatus(head, RefundApplicationStatus.PENDING_APPROVAL, "只有待审的申请可以驳回");
      if (note.isEmpty()) {
        throw new IllegalArgumentException("驳回必须说明理由：不写理由的驳回在事后无法解释「为什么不退」");
      }
      // ⚠️ 驳回记在 rejected_by / rejected_at，**不写** first_approved_* ——
      //   那两列的语义是「审下来同意」，共用会让「同意还是驳回」出现两个答案。
      transition(id, head.version(), "REJECTED",
          "rejected_by = ?::uuid, rejected_at = ?::timestamptz, approval_note = ?,",
          actor, at, note);
      return detail(id);
    }

    if (head.applicationStatus().equals("PENDING_APPROVAL")) {
      expectStatus(head, RefundApplicationStatus.PENDING_APPROVAL, "只有待审的申请可以初审");
      // 服务层先拦一道（给人话），DB CHECK 再兜一道（给绕过服务层直连 SQL 的路径）。
      if (head.submittedBy() != null && head.submittedBy().equals(actor)) {
        throw new IllegalArgumentException("初审人不能与提交人是同一个人：自己提自己批等于没审批");
      }
      RefundPolicyView p = policy(head.currency(), head.refundAmount());
      // ⚠️ fail-closed（DB 触发器同一条纪律）：没有配置时一律双人签。
      if (p.requiresDualApproval()) {
        transition(id, head.version(), "SECOND_APPROVAL_PENDING",
            "first_approved_by = ?::uuid, first_approved_at = ?::timestamptz, approval_note = ?,",
            actor, at, note);
      } else {
        transition(id, head.version(), "APPROVED",
            "first_approved_by = ?::uuid, first_approved_at = ?::timestamptz, approval_note = ?,",
            actor, at, note);
      }
      return detail(id);
    }

    expectStatus(head, RefundApplicationStatus.SECOND_APPROVAL_PENDING, "只有待复审的申请可以复审");
    if (head.firstApprovedBy() != null && head.firstApprovedBy().equals(actor)) {
      throw new IllegalArgumentException("复审人不能与初审人是同一个人：双人签必须是两个人");
    }
    transition(id, head.version(), "SECOND_APPROVED",
        "second_approved_by = ?::uuid, second_approved_at = ?::timestamptz, approval_note = ?,",
        actor, at, note);
    return detail(id);
  }

  /**
   * 交财务执行（蓝图「提交审批后交财务执行」）。
   *
   * <p><b>必须指明一条已存在的执行记录</b>：本模块只<b>引用</b> 11.3.1.2 的表（V159），
   * 不创建它 —— 每张事实表只有一个写入所有者。执行记录的状态、订单与售后单归属都要在这里真查一次。
   */
  public RefundDetail dispatch(UUID id, RefundDispatch request, UUID actor) {
    if (request == null || request.executionId() == null) {
      throw new IllegalArgumentException(
          "交财务执行必须指明一条退款执行记录（executionId）：本模块只引用 11.3.1.2 的执行记录，不创建它");
    }
    Record r = head(id);
    RefundHead head = head(r);
    if (!head.applicationStatus().equals("APPROVED") && !head.applicationStatus().equals("SECOND_APPROVED")) {
      throw new IllegalArgumentException("只有已批准的申请可以交财务执行（当前状态："
          + head.applicationStatusLabel() + "）");
    }
    checkVersion(head, request.expectedVersion());

    UUID tenant = currentTenant();
    Record exec = sql.fetchOne(
        "select execution_state, order_id, case_id from refund_execution"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        tenant, request.executionId());
    if (exec == null) {
      throw new IllegalArgumentException("退款执行记录不存在或不属于当前租户：" + request.executionId());
    }
    String state = exec.get("execution_state", String.class);
    UUID execOrder = exec.get("order_id", UUID.class);
    UUID execCase = exec.get("case_id", UUID.class);
    if (!execOrder.equals(head.orderId())
        || (execCase != null && !execCase.equals(head.caseId()))) {
      throw new IllegalArgumentException("退款执行记录的订单 / 售后单与本申请不一致");
    }
    if (!List.of("NOT_INITIATED", "IN_FLIGHT", "UNKNOWN").contains(state)) {
      throw new IllegalArgumentException("退款执行记录已定论（" + state + "），不能再挂到新的申请上");
    }

    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    transition(id, head.version(), "EXECUTING",
        "dispatched_by = ?::uuid, dispatched_at = ?::timestamptz, execution_id = ?::uuid,",
        actor, at, request.executionId());
    return detail(id);
  }

  /**
   * 登记执行结果：成功 / 失败。
   *
   * <p>⚠️ <b>三条硬规则</b>：
   *
   * <ol>
   *   <li>{@code FINANCE_MODULE_12} 在 12 财务交付前<b>拒绝</b>（照抄 V159 对 report_source 的处理）——
   *       当前真正能写的是平台侧回报与人工登记。</li>
   *   <li>挂了执行记录时，<b>结果必须与那条记录的真实状态一致</b>：
   *       记录停在 IN_FLIGHT / UNKNOWN / DISCREPANCY 时**不许**在这里宣布成功或失败 ——
   *       这就是蓝图加粗句「外部退款超时不等于失败」在本模块的落点。</li>
   *   <li>成功必须给出外部交易号，失败必须给出原因（没有可复核凭据的结果等于没结果）。</li>
   * </ol>
   */
  public RefundDetail recordOutcome(UUID id, RefundExecutionOutcome request, UUID actor) {
    if (request == null) {
      throw new IllegalArgumentException("执行结果不能为空");
    }
    Record r = head(id);
    RefundHead head = head(r);
    expectStatus(head, RefundApplicationStatus.EXECUTING, "只有已派发的申请可以登记执行结果");
    checkVersion(head, request.expectedVersion());

    RefundApplicationStatus target =
        parseEnum(RefundApplicationStatus.class, request.result(), "执行结果");
    if (target != RefundApplicationStatus.SUCCEEDED && target != RefundApplicationStatus.FAILED) {
      throw new IllegalArgumentException("执行结果只能是 SUCCEEDED 或 FAILED，收到 " + request.result());
    }
    RefundOutcomeSource source =
        parseEnum(RefundOutcomeSource.class, request.source(), "结果来源");
    if (source == RefundOutcomeSource.FINANCE_MODULE_12) {
      throw new IllegalArgumentException("12 财务尚未交付，执行结果不能声称来自 12.2.2.2 付款执行回报："
          + "当前可写的是平台侧回报（PLATFORM_SYNC）与人工登记（MANUAL_OPERATOR）");
    }

    // 挂着的执行记录是最终事实：它没定论就不许在这里单方面下结论。
    if (head.executionId() != null) {
      Record exec = sql.fetchOne(
          "select execution_state, resolved_at from refund_execution"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          currentTenant(), head.executionId());
      String state = exec == null ? null : exec.get("execution_state", String.class);
      boolean execResolved = exec != null && exec.get("resolved_at", OffsetDateTime.class) != null;
      if (!execResolved) {
        throw new IllegalArgumentException("关联的退款执行记录还没定论（当前状态：" + state + "），"
            + "不能在这里单方面宣布成功或失败：**外部退款超时不等于失败**，"
            + "重复发起退款会造成实质资损。先在 11.3.1.2 查证交易并让执行记录定论");
      }
      if (target == RefundApplicationStatus.SUCCEEDED && !"SUCCEEDED".equals(state)) {
        throw new IllegalArgumentException("关联的退款执行记录状态是 " + state + "，与「成功」不一致");
      }
      if (target == RefundApplicationStatus.FAILED && !"FAILED".equals(state)) {
        throw new IllegalArgumentException("关联的退款执行记录状态是 " + state + "，与「失败」不一致");
      }
    }

    String externalRef = blankToEmpty(request.externalTransactionRef());
    String failureReason = request.failureReason() == null ? "" : request.failureReason().trim();
    if (target == RefundApplicationStatus.SUCCEEDED && externalRef.isEmpty()) {
      throw new IllegalArgumentException("登记成功必须给出外部交易号："
          + "说「确认退成功了」却给不出交易号，这条结果就没有可复核的凭据");
    }
    if (target == RefundApplicationStatus.FAILED && failureReason.isEmpty()) {
      throw new IllegalArgumentException("登记失败必须说明原因（蓝图输出句点名「失败原因」）");
    }

    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    transition(id, head.version(), target.name(),
        "outcome_source = ?, outcome_recorded_by = ?::uuid, outcome_recorded_at = ?::timestamptz,"
            + " failure_reason = ?,",
        source.name(), actor, at, failureReason);
    return detail(id);
  }

  /** 人工撤销：草稿 / 待审 / 已批都可以撤（钱还没出去）。**终态不可撤** —— 已经退出去的钱撤不回来。 */
  public RefundDetail cancel(UUID id, RefundCancel request, UUID actor) {
    if (request == null || request.reason() == null || request.reason().isBlank()) {
      throw new IllegalArgumentException("撤销必须说明理由");
    }
    Record r = head(id);
    RefundHead head = head(r);
    RefundApplicationStatus status =
        parseEnum(RefundApplicationStatus.class, head.applicationStatus(), "申请状态");
    if (status.terminal() || status == RefundApplicationStatus.EXECUTING) {
      throw new IllegalArgumentException("只有草稿 / 待审 / 已批的申请可以撤销（当前状态："
          + head.applicationStatusLabel() + "）：已派发的钱在途上，撤不掉");
    }
    checkVersion(head, request.expectedVersion());

    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    transition(id, head.version(), "CANCELLED",
        "cancelled_by = ?::uuid, cancelled_at = ?::timestamptz, cancel_reason = ?,",
        actor, at, request.reason().trim());
    return detail(id);
  }

  // ================================================================ 内部

  /**
   * 状态流转的唯一入口：乐观锁 + 版本自增。
   *
   * <p>⚠️ {@code extra} 是**固定文本**片段（列赋值），值一律走占位符 ——
   * 列名与顺序来自服务层常量，不来自任何请求内容。
   */
  private void transition(
      UUID id, long expectedVersion, String toStatus, String extra, Object... extraValues) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    List<Object> binds = new ArrayList<>();
    binds.add(toStatus);
    binds.addAll(java.util.Arrays.asList(extraValues));
    binds.add(at);
    binds.add(currentTenant());
    binds.add(id);
    binds.add(expectedVersion);
    int updated = sql.execute(
        "update refund_application set application_status = ?, " + extra
            + " version = version + 1, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid and version = ?",
        binds.toArray());
    if (updated == 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "退款申请已被他人修改（expectedVersion=" + expectedVersion
              + "），请刷新后重试");
    }
  }

  /**
   * 单据头 + 上游编号。
   *
   * <p>⚠️ <b>必须带三个 LEFT JOIN</b>：{@link #head(Record)} 要读 {@code case_no / rma_no /
   * order_no}，而它们分别在 aftersales_case / rma_authorization / sales_order 上。
   * 只 {@code select r.*} 会在 mapper 里抛「Field "case_no" is not contained in row type」——
   * 报错点在服务层而根因在查询，二者隔得很远。
   */
  private Record head(UUID id) {
    Record r = sql.fetchOne(
        "select r.*, c.case_no, rm.rma_no, o.order_no"
            + " from refund_application r"
            + " left join aftersales_case c on c.tenant_id = r.tenant_id and c.id = r.case_id"
            + " left join rma_authorization rm on rm.tenant_id = r.tenant_id and rm.id = r.rma_id"
            + " left join sales_order o on o.tenant_id = r.tenant_id and o.id = r.order_id"
            + " where r.tenant_id = ?::uuid and r.id = ?::uuid",
        currentTenant(), id);
    if (r == null) {
      throw new IllegalArgumentException("退款申请不存在或不属于当前租户：" + id);
    }
    return r;
  }

  private void checkVersion(RefundHead head, Long expectedVersion) {
    if (expectedVersion == null) {
      throw new IllegalArgumentException("必须带上 expectedVersion（乐观锁）："
          + "两个人同时点「提交 / 审批」时，缺了它第二次会把前一次的决定静默覆盖掉");
    }
    if (expectedVersion != head.version()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "退款申请已被他人修改（当前版本 " + head.version()
              + "，你看到的是 " + expectedVersion + "），请刷新后重试");
    }
  }

  private void expectStatus(RefundHead head, RefundApplicationStatus expected, String message) {
    if (!head.applicationStatus().equals(expected.name())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, message + "（当前状态：" + head.applicationStatusLabel() + "）");
    }
  }

  private RefundRow row(Record r, UUID tenant) {
    RefundHead head = head(r);
    BigDecimal refundable = BigDecimal.ZERO;
    // ⚠️ 每行的可退余额来自列表查询里的 **lateral 子查询**（见 {@link #list}），
    //   不在这里二次查询 —— 一行一次往返会把列表变成 N+1，
    //   而页大小上限 200 意味着一次列表请求能打出去 200 条额外 SQL。
    if (r.get("refundable_amount", BigDecimal.class) != null) {
      refundable = r.get("refundable_amount", BigDecimal.class).max(BigDecimal.ZERO);
    }
    String timeliness = r.get("timeliness_state", String.class);
    return new RefundRow(
        head, timeliness, r.get("remaining_seconds", Long.class), refundable);
  }

  private RefundHead head(Record r) {
    RefundApplicationStatus status =
        parseEnum(RefundApplicationStatus.class, r.get("application_status", String.class), "申请状态");
    RefundMethod method = parseEnum(RefundMethod.class, r.get("refund_method", String.class), "退款方式");
    RefundReason reason = parseEnum(RefundReason.class, r.get("refund_reason", String.class), "退款原因");
    return new RefundHead(
        r.get("id", UUID.class), r.get("application_no", String.class),
        r.get("case_id", UUID.class), nullable(r.get("case_no", String.class)),
        r.get("rma_id", UUID.class), nullable(r.get("rma_no", String.class)),
        r.get("order_id", UUID.class), nullable(r.get("order_no", String.class)),
        status.name(), status.label(), method.name(), method.label(),
        r.get("refund_amount", BigDecimal.class), r.get("currency", String.class),
        reason.name(), reason.label(), nullable(r.get("refund_reason_note", String.class)),
        nullable(r.get("original_payment_ref", String.class)),
        nullable(r.get("platform_refund_ref", String.class)),
        r.get("submitted_by", UUID.class), r.get("submitted_at", OffsetDateTime.class),
        r.get("first_approved_by", UUID.class), r.get("first_approved_at", OffsetDateTime.class),
        r.get("second_approved_by", UUID.class), r.get("second_approved_at", OffsetDateTime.class),
        nullable(r.get("approval_note", String.class)),
        r.get("dispatched_by", UUID.class), r.get("dispatched_at", OffsetDateTime.class),
        r.get("execution_id", UUID.class), null, nullable(r.get("outcome_source", String.class)),
        r.get("outcome_recorded_by", UUID.class),
        r.get("outcome_recorded_at", OffsetDateTime.class),
        nullable(r.get("failure_reason", String.class)), nullable(r.get("cancel_reason", String.class)),
        r.get("created_by", UUID.class), r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class), r.get("version", Long.class));
  }

  /** 当前可执行动作。不可用时把理由原样带出来（按钮变灰而不给理由会让人以为系统坏了）。 */
  private List<RefundActionEntry> actions(RefundHead head) {
    RefundApplicationStatus status =
        parseEnum(RefundApplicationStatus.class, head.applicationStatus(), "申请状态");
    boolean draft = status == RefundApplicationStatus.DRAFT;
    boolean pending = status == RefundApplicationStatus.PENDING_APPROVAL;
    boolean secondPending = status == RefundApplicationStatus.SECOND_APPROVAL_PENDING;
    boolean approved = status.approved();
    String terminal =
        status.terminal()
            ? "申请已终结（" + status.label() + "）：留痕是审计与纠纷的原材料，不能再改"
            : null;

    List<RefundActionEntry> actions = new ArrayList<>();
    actions.add(new RefundActionEntry("CREATE", "发起退款申请", true, "POST",
        "/trade/refund-applications", false, null, null, null));
    actions.add(new RefundActionEntry("CHECK_BALANCE", "校验可退余额", true, "GET",
        "/trade/refund-applications/refundable-balance?caseId={uuid}", false, null, null, null));
    actions.add(new RefundActionEntry("UPDATE_DRAFT", "修改草稿要素", draft, "PUT",
        "/trade/refund-applications/{id}", false,
        terminal != null ? terminal : (draft ? null : "只有草稿可改：已提交的申请是一份待审凭据"),
        null, null));
    actions.add(new RefundActionEntry("SUBMIT", "提交审批", draft, "POST",
        "/trade/refund-applications/{id}/submit", false,
        terminal != null ? terminal : (draft ? null : "只有草稿可以提交审批"), null, null));
    actions.add(new RefundActionEntry("FIRST_APPROVE", "初审（同意 / 驳回）", pending, "POST",
        "/trade/refund-applications/{id}/decide", true,
        terminal != null ? terminal : (pending ? null : "只有待审的申请可以初审"), null, null));
    actions.add(new RefundActionEntry("SECOND_APPROVE", "复审（同意 / 驳回）", secondPending, "POST",
        "/trade/refund-applications/{id}/decide", true,
        terminal != null ? terminal : (secondPending ? null : "只有待复审的申请可以复审"), null, null));
    actions.add(new RefundActionEntry("DISPATCH", "交财务执行", approved, "POST",
        "/trade/refund-applications/{id}/dispatch", false,
        terminal != null ? terminal : (approved ? null : "只有已批准的申请可以交财务执行"), null, null));
    actions.add(new RefundActionEntry("RECORD_OUTCOME", "登记执行结果（成功 / 失败）",
        status == RefundApplicationStatus.EXECUTING, "POST",
        "/trade/refund-applications/{id}/execution-outcome", true,
        terminal != null ? terminal
            : (status == RefundApplicationStatus.EXECUTING
                ? null
                : "只有已派发的申请可以登记结果"),
        null, null));
    actions.add(new RefundActionEntry("CANCEL", "撤销申请",
        draft || pending || secondPending || approved, "POST",
        "/trade/refund-applications/{id}/cancel", true,
        terminal != null ? terminal : (approved || pending || secondPending || draft
            ? null
            : "已派发的钱在途上，撤不掉"),
        null, null));
    actions.add(new RefundActionEntry("VERIFY_TRANSACTION", "查证交易再重试", false, null, null,
        false, null, "11.3.1.2 退款状态",
        "**外部退款超时不等于失败**：执行记录停在 UNKNOWN 时，重试会造成实质资损。"
            + "查证（查这笔交易到底出没出去）属 11.3.1.2（已交付，V159），本模块刻意不代做。"));
    actions.add(new RefundActionEntry("PAY_OUT", "真实付款执行", false, null, null, false, null,
        "12.2.2.2 付款执行",
        "12 财务整卷未交付。本模块的「执行中」只说明**已派发**，不代表钱已经退了，"
            + "更不代表原收款关联已被保留。"));
    return actions;
  }

  private String nextApplicationNo() {
    long n = sql.fetchOne("select nextval('refund_application_no_seq')").get(0, Long.class);
    return "RA" + n;
  }

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  // ================================================================ 小工具

  private static BigDecimal requirePositiveAmount(BigDecimal value) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException("退款金额不能为空且必须大于 0（金额一律 BigDecimal + numeric，禁浮点）");
    }
    return value.setScale(4, java.math.RoundingMode.HALF_UP);
  }

  private static String requireCurrency(String raw) {
    if (raw == null || !raw.trim().matches("^[A-Za-z]{3}$")) {
      throw new IllegalArgumentException("币种必须是三位大写字母（ISO 4217）");
    }
    return raw.trim().toUpperCase();
  }

  private static <E extends Enum<E> & RefundApplicationModels.Labelled> E parseEnum(Class<E> type, String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    for (E e : type.getEnumConstants()) {
      if (e.name().equalsIgnoreCase(raw.trim())) {
        return e;
      }
    }
    StringBuilder allowed = new StringBuilder();
    for (E e : type.getEnumConstants()) {
      allowed.append(allowed.isEmpty() ? "" : " / ").append(e.name());
    }
    throw new IllegalArgumentException(label + "只能是 " + allowed + "，收到 " + raw);
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  /** 空串在响应里报成 null：列表与详情里「没填」应显示为「—」而不是一段空白。 */
  private static String nullable(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
