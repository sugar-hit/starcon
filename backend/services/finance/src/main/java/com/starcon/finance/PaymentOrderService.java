package com.starcon.finance;

import static com.starcon.finance.PaymentOrderModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.2.2.1 付款单与审批。
 *
 * <p>蓝图 12-finance.md：「<b>发起付款申请：选择应付明细、付款账户、币种与汇率、付款方式、分批安排，
 * 走分级审批。</b>输入：付款申请；审批规则（金额阈值）；汇率快照。
 * 输出：<b>付款单（草稿/待审/已批/执行中/已付/失败）+ 审批记录</b>。
 * 关系：上游 = 12.1.2.1、12.1.2.2；下游 = 12.2.2.2、17 审批。
 * <b>付款审批是资金安全的第一道控制</b>，大额付款必须双人复核或多级审批。」
 *
 * <p><b>本模块的四条设计主线</b>：
 *
 * <ol>
 *   <li><b>审批引擎不归本模块。</b>本模块只做「提交」与「读回」两件事，
 *       真正的审批判定、金额阈值分支、并行/串行流转全在 identity 的 17 审批里
 *       （见 {@link ApprovalPort}）。本模块**不提供**本地的「审批通过 / 驳回」端点 ——
 *       那会造出第二套审批引擎，两套引擎必然打架。
 *   <li><b>「已付」写不出来。</b>付款单有蓝图要求的完整六态，但
 *       {@code execution_available} 被表级 CHECK 钉死为 false ⇒ 触发器拒绝写入
 *       EXECUTING / PAID / FAILED ⇒ 那三个状态的写入权属 12.2.2.2（未交付）。
 *       <b>审批通过 ≠ 钱已付出。</b>
 *   <li><b>付款必须精确到应付明细行。</b>明细行的 {@code payable_line_id} 是 NOT NULL 外键，
 *       指向 12.1.2.1 的 {@code finance_payable_line}（同服务同库）⇒
 *       「只挂单头不指到行」在数据层写不出来。
 *   <li><b>汇率快照建单时冻结。</b>汇率事实来自 catalog 的 5.2.2.1（跨服务授权 HTTP API），
 *       读回来就写进单头并冻结；之后汇率变了也不改这张单。
 * </ol>
 *
 * <p><b>跨模块边界</b>：上游 12.1.2.1（应付）与 12.1.2.2（付款计划）在本服务内 ⇒ 读它们的表
 * 走 jOOQ 显式 SQL，但对上游表<b>只读</b>：不写、不改结构、不加触发器、不改 GRANT。
 * ⚠️ <b>特别地，本模块不写 {@code finance_payable_line.paid_amount}</b> ——
 * 「已付」这个事实必须等到 12.2.2.2 真的把钱付出去那一刻才成立。
 */
@Service
public class PaymentOrderService {

  private static final Logger logger = LoggerFactory.getLogger(PaymentOrderService.class);

  /** 当前 UTC 日历日（排期与到期判定的基准日，与 12.1.2.1 / 12.1.2.2 同一口径）。 */
  static final LocalDate TODAY_UTC = LocalDate.now(ZoneOffset.UTC);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 审批提交时在 identity 侧登记的来源系统。 */
  static final String APPROVAL_SOURCE_SYSTEM = "finance";

  private final DSLContext sql;
  private final ApprovalPort approvals;
  private final ExchangeRatePort rates;

  public PaymentOrderService(DSLContext sql, ApprovalPort approvals, ExchangeRatePort rates) {
    this.sql = sql;
    this.approvals = approvals;
    this.rates = rates;
  }

  // ================================================================ 内部结构

  /** 应付明细行的事实（只读 12.1.2.1）。 */
  private record PayableLineFact(
      UUID payableLineId,
      UUID payableId,
      String payableNo,
      int lineNo,
      String description,
      String skuCode,
      String currency,
      BigDecimal amount,
      BigDecimal paidAmount,
      LocalDate dueDate,
      String payableStatus,
      PaymentCounterpartyType counterpartyType,
      UUID counterpartyRef,
      String counterpartyName) {}

  /** 算好的一张付款单（还没写）。 */
  private record DraftOrder(
      PaymentCounterpartyType counterpartyType,
      UUID counterpartyRef,
      String counterpartyName,
      String currency,
      BigDecimal amount,
      String baseCurrency,
      BigDecimal exchangeRate,
      PaymentMethod paymentMethod,
      UUID paymentAccountId,
      String paymentAccountName,
      String paymentAccountCurrency,
      UUID paymentPlanId,
      String paymentPlanNo,
      String note,
      List<PayableLineFact> lines,
      List<InstallmentDraft> installments) {}

  /** 一笔分批（算好了、还没写）。 */
  private record InstallmentDraft(int seq, LocalDate plannedPaidDate, String currency, BigDecimal amount) {}

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 页面必须能分开两件事：<b>「上游未交付」</b>（付款执行、资金账户）
   * 与<b>「本模块已经做完」</b>（付款单、审批对接、汇率快照、分批安排）。
   */
  public FinancePaymentOrderCapabilities capabilities() {
    var capabilities = new ArrayList<FinancePaymentOrderCapability>();
    var gaps = new ArrayList<FinancePaymentOrderDownstreamGap>();

    capabilities.add(
        new FinancePaymentOrderCapability(
            "12.2.2.1",
            "付款单表",
            true,
            "finance_payment_order / finance_payment_order_line / finance_payment_order_installment / "
                + "finance_payment_order_event",
            "",
            "付款单已落地：数据落 PostgreSQL 16，查询走 jOOQ 显式 SQL，全链路 BigDecimal + NUMERIC，无浮点。"
                + "状态机是蓝图原文的六态（草稿/待审/已批/执行中/已付/失败），"
                + "**但 EXECUTING / PAID / FAILED 本模块写不出来**（见下一条）。"));

    capabilities.add(
        new FinancePaymentOrderCapability(
            "12.2.2.1",
            "选择应付明细（精确到行）",
            true,
            "finance_payment_order_line.payable_line_id（NOT NULL 外键 → 12.1.2.1 的 finance_payable_line）",
            "",
            "明细行必须指到**应付明细行**，不是应付单头 ⇒ 「只挂单头不指到行」在数据层写不出来。"
                + "同一应付明细行不会被两张在册付款单同时占用（部分唯一索引）。"
                + "单头金额必须等于明细合计、分批合计（服务层整体校验，跨行约束）。"));

    capabilities.add(
        new FinancePaymentOrderCapability(
            "12.2.2.1",
            "分级审批（对接 17 审批）",
            true,
            "identity：GET /identity/approval-flows/active、POST /identity/approval-instances、"
                + "GET /identity/approval-records/{id}",
            "",
            "审批引擎**不归本模块**：提交走 identity 的授权端点，金额阈值分支由 identity 的 "
                + "approval_flow_amount_branch **真实生效**（起始节点是它按金额选出来的），"
                + "本模块把审批链路**镜像**回 finance_payment_approval_record。"
                + "**双人复核 / 多级审批**由 identity 审批流的节点结构承担："
                + "同阶段 PARALLEL 节点 = 双人复核，多阶段 SERIAL 节点 = 多级审批；"
                + "本模块把该结构如实展示，**不自己判定**。"
                + "需要调用方具备 identity.approval_flow.read 与 identity.approval_record.read 权限。"
                + "⚠️ 单据类型「PAYMENT_ORDER」**未注册生效审批流时拒绝提交** —— "
                + "付款是资金动作，不能按「未注册即默认放行」处理。"));

    capabilities.add(
        new FinancePaymentOrderCapability(
            "12.2.2.1",
            "审批记录",
            true,
            "finance_payment_approval_record（identity 审批链路的本地镜像，append-only）",
            "",
            "审批记录是**镜像**不是引擎：实例、节点、动作、审批人、每步停留时长全部来自 identity。"
                + "按 (approval_instance_id, action_id) 去重 ⇒ 反复同步不会灌出重复行。"
                + "本地留一份是为了让付款单详情不必每次渲染都跨服务调一次 identity，"
                + "同时让「谁批的、批到哪一步」成为付款单自己的举证凭据。"));

    capabilities.add(
        new FinancePaymentOrderCapability(
            "12.2.2.1",
            "币种与汇率快照",
            true,
            "finance_payment_order.exchange_rate / base_currency；事实来自 catalog 5.2.2.1 "
                + "GET /catalog/exchange-rates/latest",
            "",
            "汇率**快照**建单时冻结，之后不可改（触发器挡）。"
                + "上游只登记了反方向时取其倒数，并在结果上打 inverted=true —— "
                + "页面必须让人看见「这个汇率是推导出来的」。"
                + "两个方向都查不到就是**真没有**，显式失败，绝不回退到「最近的一版」。"
                + "本位币金额是**生成列** round(amount * exchange_rate, 4) ⇒ 永远自洽。"));

    capabilities.add(
        new FinancePaymentOrderCapability(
            "12.2.2.1",
            "付款方式与分批安排",
            true,
            "finance_payment_order.payment_method；finance_payment_order_installment",
            "",
            "付款方式取自蓝图点名的跨境通道（银行电汇 / PayPal / Stripe / PingPong / 其他）。"
                + "分批安排是真落库：每批一个计划付款日与金额，"
                + "**分批金额之和 = 单头金额**（服务层整体校验）。"
                + "分批的执行态（执行中/已付/失败）同样归 12.2.2.2。"));

    capabilities.add(
        new FinancePaymentOrderCapability(
            "12.2.2.2",
            "付款执行（把已批的付款单真的付出去）",
            false,
            "写路径未开放：execution_available 由表级 CHECK 钉死为 false，"
                + "触发器 guard_finance_payment_order_state 拒绝写入 EXECUTING / PAID / FAILED",
            "12.2.2.2",
            "蓝图关系写死「下游 = 12.2.2.2」。本模块交付的是**申请 + 审批**，"
                + "**交付不了执行**。审批通过 ≠ 钱已付出："
                + "行级 paid_amount 由 12.2.2.2 在真正付出钱的那一刻回写。"
                + "本模块**刻意不写** paid_amount —— 审批阶段就写会让「已付」早于它真正发生。"));

    capabilities.add(
        new FinancePaymentOrderCapability(
            "12.7.1.1",
            "付款账户（资金账户登记）",
            false,
            "本模块只登记引用 + 名称 + 币种快照；payment_account_owner_module 恒为 '12.7.1.1'",
            "12.7.1.1",
            "12.7.1.1 资金账户（未交付）⇒ 本模块**不建账户表、不做账户余额** —— "
                + "那会造出第二份资金账户真相，而两份真相必然打架。"
                + "它交付后由它接管账户登记与余额，本表结构不用改。"));

    gaps.add(
        new FinancePaymentOrderDownstreamGap(
            "12.2.2.2",
            "付款执行",
            "finance",
            false,
            "下游未交付",
            "交接面：① finance_payment_order.status 的 EXECUTING / PAID / FAILED 三态已备好，"
                + "但 execution_available 的 CHECK 与 guard_finance_payment_order_state 触发器"
                + "把写入挡在外面 —— 12.2.2.2 交付时**接管**这两个守卫；"
                + "② finance_payment_order_installment 同理（守卫函数 "
                + "guard_finance_payment_order_installment_state）；"
                + "③ 行级 paid_amount（finance_payable_line，V340 列名）由 12.2.2.2 在付款成功时回写；"
                + "④ 本模块**不碰** V343 的 finance_payment_plan / finance_payment_batch，"
                + "那两张表 execution_available 恒为 false 的 CHECK 一个字都不动。"));

    gaps.add(
        new FinancePaymentOrderDownstreamGap(
            "12.7.1.1",
            "资金账户",
            "finance",
            false,
            "下游未交付",
            "付款账户在本模块只是引用 + 名称 + 币种快照，没有账户表也没有余额。"
                + "12.7.1.1 交付后由它接管账户登记与余额，finance_payment_order 结构不用改。"));

    gaps.add(
        new FinancePaymentOrderDownstreamGap(
            "17.4.1.1",
            "审批流定义（租户侧配置）",
            "identity",
            true,
            "已交付 · 需租户配置",
            "identity 侧的审批流定义、审批规则、待我审批、审批记录**四个模块都已交付**。"
                + "本模块已接上它们（见 capabilities 的「分级审批」一条）。"
                + "⚠️ 但**租户要自己在 17.4.1.1 注册一个 document_type_code = 'PAYMENT_ORDER' "
                + "的 ACTIVE 流程**，否则付款单无法提交 —— 这是配置动作，不是代码缺口。"));

    return new FinancePaymentOrderCapabilities(capabilities, gaps);
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinancePaymentOrderOptions options() {
    var currencies =
        sql.fetch("select distinct currency from finance_payment_order order by currency")
            .map(r -> r.get(0, String.class));
    var statuses =
        sql.fetch("select distinct status from finance_payment_order order by status")
            .map(r -> r.get(0, String.class));
    var approvalStatuses =
        sql.fetch("select distinct approval_status from finance_payment_order order by approval_status")
            .map(r -> r.get(0, String.class));
    var methods =
        sql.fetch(
                "select distinct payment_method from finance_payment_order order by payment_method")
            .map(r -> r.get(0, String.class));
    var counterparties =
        sql.fetch(
                "select distinct counterparty_ref, counterparty_name from finance_payment_order"
                    + " where counterparty_ref is not null"
                    + " order by counterparty_name, counterparty_ref")
            .map(r -> new FinancePaymentOrderOptionRef(r.get(0, UUID.class).toString(), r.get(1, String.class)));
    var plans =
        sql.fetch(
                "select p.id, p.plan_no from finance_payment_order o"
                    + " join finance_payment_plan p on p.tenant_id = o.tenant_id and p.id = o.payment_plan_id"
                    + " order by p.plan_no")
            .map(r -> new FinancePaymentOrderOptionRef(r.get(0, UUID.class).toString(), r.get(1, String.class)));
    return new FinancePaymentOrderOptions(
        currencies, statuses, approvalStatuses, methods, counterparties, plans);
  }

  // ================================================================ 列表 / 详情

  /** 付款单列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
  public FinancePaymentOrderPage page(PaymentOrderQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from finance_payment_order o" + clause, binds.toArray())
            .get(0, Long.class);
    List<FinancePaymentOrderRow> items =
        sql.fetch(
                "select o.* from finance_payment_order o"
                    + clause
                    + " order by o.created_at desc, o.id desc limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::orderRow);

    var summaries = summaryByCurrency(where, binds);
    long pending =
        summaries.stream().mapToLong(FinancePaymentOrderCurrencySummary::pendingApprovalCount).sum();
    return new FinancePaymentOrderPage(items, p, s, total, summaries, pending);
  }

  /** 按币种的汇总。 */
  private List<FinancePaymentOrderCurrencySummary> summaryByCurrency(
      List<String> where, List<Object> binds) {
    // ⚠️ 没有筛选条件时**不能**拼出空 WHERE —— 那会变成 `... where  group by ...` 的语法错误。
    var whereClause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    return sql.fetch(
            "select o.currency, sum(o.amount) as amount, count(*) as cnt,"
                + " count(*) filter (where o.status = 'PENDING_APPROVAL') as pending_count,"
                + " count(*) filter (where o.status = 'APPROVED') as approved_count,"
                + " count(*) filter (where o.status = 'DRAFT') as draft_count"
                + " from finance_payment_order o"
                + whereClause
                + " group by o.currency order by o.currency",
            binds.toArray())
        .map(
            r ->
                new FinancePaymentOrderCurrencySummary(
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("cnt", Long.class),
                    r.get("pending_count", Long.class),
                    r.get("approved_count", Long.class),
                    r.get("draft_count", Long.class)));
  }

  private void applyFilters(PaymentOrderQuery q, List<String> where, List<Object> binds) {
    if (q.status() != null) {
      where.add("o.status = ?");
      binds.add(q.status().name());
    }
    if (q.approvalStatus() != null) {
      where.add("o.approval_status = ?");
      binds.add(q.approvalStatus().name());
    }
    if (q.currency() != null && !q.currency().isBlank()) {
      where.add("o.currency = ?");
      binds.add(q.currency());
    }
    if (q.counterpartyType() != null) {
      where.add("o.counterparty_type = ?");
      binds.add(q.counterpartyType().name());
    }
    if (q.counterpartyRef() != null) {
      where.add("o.counterparty_ref = ?");
      binds.add(q.counterpartyRef());
    }
    if (q.paymentMethod() != null) {
      where.add("o.payment_method = ?");
      binds.add(q.paymentMethod().name());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add("(o.order_no ilike ? or o.counterparty_name ilike ? or o.note ilike ?)");
      var like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  /** 付款单详情：单头 + 明细 + 分批 + 事件流 + 审批记录。 */
  public FinancePaymentOrderDetail detail(UUID id) {
    var order = orderRow(requireOrder(id));
    return new FinancePaymentOrderDetail(
        order,
        lines(id),
        installments(id),
        events(id),
        approvalRecords(id));
  }

  // ================================================================ 可选应付明细

  /**
   * 某张应付单下**可被选中**的明细行（只读 12.1.2.1）。
   *
   * <p>每行带**未付余额**与**是否已被在册付款单占用** ⇒ 前端据此告诉用户哪一行能选、
   * 哪一行已经被占了（占用者就是那笔在册付款单，附单号）。
   */
  public List<FinancePaymentPayableLineOption> payableLines(UUID payableId) {
    var payable =
        sql.fetchOne(
            "select id, payable_no, due_date, status, counterparty_name"
                + " from finance_payable where id = ?",
            payableId);
    if (payable == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "应付单不存在");
    }
    var locked = lockedLines(payableId);
    return sql.fetch(
            "select l.id, l.payable_id, l.line_no, l.description, l.sku_code, p.currency,"
                + " l.amount, l.paid_amount"
                + " from finance_payable_line l"
                + " join finance_payable p on p.tenant_id = l.tenant_id and p.id = l.payable_id"
                + " where l.payable_id = ? order by l.line_no",
            payableId)
        .map(
            r -> {
              var lineId = r.get("id", UUID.class);
              var lock = locked.get(lineId);
              var amount = r.get("amount", BigDecimal.class);
              var paid = r.get("paid_amount", BigDecimal.class);
              return new FinancePaymentPayableLineOption(
                  lineId,
                  r.get("payable_id", UUID.class),
                  payable.get("payable_no", String.class),
                  r.get("line_no", Integer.class),
                  r.get("description", String.class),
                  r.get("sku_code", String.class),
                  r.get("currency", String.class),
                  amount,
                  paid,
                  amount.subtract(paid),
                  payable.get("due_date", LocalDate.class),
                  payable.get("counterparty_name", String.class),
                  lock == null ? null : lock.orderId(),
                  lock == null ? null : lock.orderNo());
            });
  }

  /** 已被在册付款单占用的应付明细行 → 占用者（付款单 id + 单号）。 */
  private Map<UUID, LockedLine> lockedLines(UUID payableId) {
    var map = new LinkedHashMap<UUID, LockedLine>();
    sql.fetch(
            "select l.payable_line_id, o.id, o.order_no"
                + " from finance_payment_order_line l"
                + " join finance_payment_order o on o.tenant_id = l.tenant_id and o.id = l.order_id"
                + " where l.payable_line_id in"
                + " (select id from finance_payable_line where payable_id = ?)"
                + "   and l.active"
                + "   and o.status not in ('PAID','FAILED')",
            payableId)
        .forEach(
            r ->
                map.put(
                    r.get("payable_line_id", UUID.class),
                    new LockedLine(r.get("id", UUID.class), r.get("order_no", String.class))));
    return map;
  }

  /** 占用者。 */
  private record LockedLine(UUID orderId, String orderNo) {}

  // ================================================================ 汇率快照

  /**
   * 汇率快照（**读** catalog 5.2.2.1）。
   *
   * <p>口径与 {@code finance_payment_order.exchange_rate} 一致：
   * 「1 baseCurrency = rate quoteCurrency」。
   */
  public FinancePaymentExchangeRateView exchangeRate(
      String baseCurrency, String quoteCurrency, String bearer) {
    var from = requireCurrency(baseCurrency, "基准币种");
    var to = requireCurrency(quoteCurrency, "报价币种");
    var snapshot = rates.latest(from, to, TODAY_UTC, bearer);
    return new FinancePaymentExchangeRateView(
        snapshot.baseCurrency(),
        snapshot.quoteCurrency(),
        snapshot.rate(),
        snapshot.effectiveDate(),
        snapshot.sourceCode(),
        snapshot.sourceName(),
        snapshot.inverted(),
        snapshot.display());
  }

  // ================================================================ 建单 / 改单

  /** 建一张付款单（草稿）。 */
  @Transactional
  public FinancePaymentOrderRow create(FinancePaymentOrderInput input, AccessService.Access actor) {
    var draft = draft(input);
    var id = UUID.randomUUID();
    var no = nextReference("PO");
    var at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_payment_order(id,tenant_id,order_no,counterparty_type,counterparty_ref,"
            + " counterparty_name,currency,amount,base_currency,exchange_rate,payment_method,"
            + " payment_account_id,payment_account_name,payment_account_currency,payment_plan_id,"
            + " payment_plan_no,status,approval_status,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT','NOT_SUBMITTED',?,?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        no,
        draft.counterpartyType().name(),
        draft.counterpartyRef(),
        draft.counterpartyName(),
        draft.currency(),
        draft.amount(),
        draft.baseCurrency(),
        draft.exchangeRate(),
        draft.paymentMethod().name(),
        draft.paymentAccountId(),
        draft.paymentAccountName(),
        draft.paymentAccountCurrency(),
        draft.paymentPlanId(),
        draft.paymentPlanNo(),
        draft.note(),
        at,
        at);
    writeChildren(id, draft, actor);
    appendEvent(id, "CREATED", "", "DRAFT", "建单", actor.userId(), at);
    return orderRow(requireOrder(id));
  }

  /** 改一张**草稿**付款单（明细与分批整体替换）。 */
  @Transactional
  public FinancePaymentOrderRow update(
      UUID id, FinancePaymentOrderInput input, AccessService.Access actor) {
    var existing = requireOrder(id);
    if (!"DRAFT".equals(existing.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "付款单已提交，不可再改：需要改请被驳回后退回草稿再改");
    }
    var draft = draft(input);
    // 整体替换明细与分批（草稿态允许）。
    sql.execute("delete from finance_payment_order_line where order_id = ?", id);
    sql.execute("delete from finance_payment_order_installment where order_id = ?", id);
    var at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_payment_order set counterparty_type=?, counterparty_ref=?, counterparty_name=?,"
            + " currency=?, amount=?, base_currency=?, exchange_rate=?, payment_method=?,"
            + " payment_account_id=?, payment_account_name=?, payment_account_currency=?,"
            + " payment_plan_id=?, payment_plan_no=?, note=?, version=version+1, updated_at=?::timestamptz"
            + " where id=?",
        draft.counterpartyType().name(),
        draft.counterpartyRef(),
        draft.counterpartyName(),
        draft.currency(),
        draft.amount(),
        draft.baseCurrency(),
        draft.exchangeRate(),
        draft.paymentMethod().name(),
        draft.paymentAccountId(),
        draft.paymentAccountName(),
        draft.paymentAccountCurrency(),
        draft.paymentPlanId(),
        draft.paymentPlanNo(),
        draft.note(),
        at,
        id);
    writeChildren(id, draft, actor);
    appendEvent(id, "UPDATED", "DRAFT", "DRAFT", "改单", actor.userId(), at);
    return orderRow(requireOrder(id));
  }

  /** 落明细行与分批。 */
  private void writeChildren(UUID orderId, DraftOrder draft, AccessService.Access actor) {
    var at = OffsetDateTime.now(ZoneOffset.UTC);
    int seq = 1;
    for (var line : draft.lines()) {
      sql.execute(
          "insert into finance_payment_order_line(id,tenant_id,order_id,line_no,payable_line_id,"
              + " description,currency,amount,payable_amount_snapshot,payable_paid_snapshot,active,"
              + " created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,true,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          actor.tenantId(),
          orderId,
          seq++,
          line.payableLineId(),
          line.description(),
          line.currency(),
          line.amount(),
          line.amount(),
          line.paidAmount(),
          at,
          at);
    }
    int iseq = 1;
    for (var installment : draft.installments()) {
      sql.execute(
          "insert into finance_payment_order_installment(id,tenant_id,order_id,seq,planned_paid_date,"
              + " currency,amount,status,created_at,updated_at)"
              + " values(?,?,?,?,?::date,?,?,'PLANNED',?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          actor.tenantId(),
          orderId,
          iseq++,
          installment.plannedPaidDate(),
          installment.currency(),
          installment.amount(),
          at,
          at);
    }
  }

  /**
   * 把入参算成一张可落库的付款单（**所有校验都在这里**）。
   *
   * <p>校验顺序刻意是「先取事实，再判一致性」：
   * 应付明细的事实必须先从 12.1.2.1 取回来（不能信客户端传的金额），
   * 然后才拿它与客户端的诉求对账。
   */
  private DraftOrder draft(FinancePaymentOrderInput input) {
    if (input == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    var currency = requireCurrency(input.currency(), "付款币种");
    var amount = money(input.amount(), "付款金额");
    if (amount.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "付款金额必须大于 0");
    }
    if (input.paymentMethod() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "付款方式必须填写");
    }

    // ---- 应付明细（事实来自 12.1.2.1） ----
    var inputs = input.lines() == null ? List.<FinancePaymentOrderLineInput>of() : input.lines();
    if (inputs.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "付款单必须选择至少一条应付明细：付款必须精确到明细行");
    }
    var facts = new ArrayList<PayableLineFact>();
    var seen = new java.util.HashSet<UUID>();
    for (var line : inputs) {
      if (line.payableLineId() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "明细缺少应付明细行");
      }
      if (!seen.add(line.payableLineId())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "同一条应付明细行被选了两次（" + line.payableLineId() + "）");
      }
      facts.add(payableLineFact(line.payableLineId()));
    }

    // 币种必须与应付一致：**不能用 EUR 的付款单去付 USD 的应付**。
    for (var fact : facts) {
      if (!fact.currency().equals(currency)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "应付明细 " + fact.payableNo() + " #" + fact.lineNo() + " 的币种是 " + fact.currency()
                + "，与付款单币种 " + currency + " 不一致：跨币种付款必须先换算成应付币种");
      }
    }
    // 付款对象必须唯一：一张付款单付给一个对象。
    var first = facts.get(0);
    for (var fact : facts) {
      if (fact.counterpartyType() != first.counterpartyType()
          || !java.util.Objects.equals(fact.counterpartyRef(), first.counterpartyRef())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "选中的应付明细属于不同的付款对象（" + first.counterpartyName() + " / "
                + fact.counterpartyName() + "）：一张付款单只能付给一个对象，请分单");
      }
    }
    if (input.counterpartyRef() != null
        && !java.util.Objects.equals(input.counterpartyRef(), first.counterpartyRef())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "指定的付款对象与所选应付明细的付款对象不一致");
    }

    // ---- 明细金额与单头金额必须一致 ----
    var lineSum = BigDecimal.ZERO;
    for (var fact : facts) {
      var chosen = inputs.stream().filter(i -> i.payableLineId().equals(fact.payableLineId())).findFirst().orElseThrow();
      var lineAmount = money(chosen.amount(), "明细付款金额");
      if (lineAmount.signum() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "明细付款金额必须大于 0");
      }
      var unpaid = fact.amount().subtract(fact.paidAmount());
      if (lineAmount.compareTo(unpaid) > 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "应付明细 " + fact.payableNo() + " #" + fact.lineNo() + " 的未付余额是 " + unpaid
                + "，本次要付 " + lineAmount + "，超出未付余额");
      }
      if (!("OPEN".equals(fact.payableStatus()) || "PARTIALLY_PAID".equals(fact.payableStatus()))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "应付明细 " + fact.payableNo() + " 所属应付单的状态是 " + fact.payableStatus()
                + "，不可付款：草稿单还没确认，已作废单不能再付");
      }
      lineSum = lineSum.add(lineAmount);
    }
    if (lineSum.compareTo(amount) != 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "单头金额 " + amount + " 与明细合计 " + lineSum + " 不一致：付款单的总账必须等于它的明细");
    }

    // ---- 付款计划（12.1.2.2） ----
    UUID planId = input.paymentPlanId();
    String planNo = "";
    if (planId != null) {
      var plan =
          sql.fetchOne(
              "select id, plan_no, payable_id from finance_payment_plan where id = ?", planId);
      if (plan == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "付款计划不存在");
      }
      var planPayable = plan.get("payable_id", UUID.class);
      boolean covered = facts.stream().anyMatch(f -> f.payableId().equals(planPayable));
      if (!covered) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "付款计划 " + plan.get("plan_no", String.class) + " 对应的应付单不在本次选择的明细里");
      }
      planNo = plan.get("plan_no", String.class);
    }

    // ---- 汇率快照 ----
    String baseCurrency = blankToNull(input.baseCurrency());
    BigDecimal rate = input.exchangeRate();
    if ((baseCurrency == null) != (rate == null)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "本位币与汇率必须成对提供：只填一个就没有唯一答案");
    }
    if (baseCurrency != null) {
      baseCurrency = requireCurrency(baseCurrency, "本位币");
      if (baseCurrency.equals(currency)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "本位币与付款币种相同，不需要汇率快照");
      }
      rate = rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
      if (rate.signum() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "汇率必须大于 0");
      }
    }

    // ---- 付款账户（12.7.1.1 未交付 ⇒ 只是引用 + 名称 + 币种快照） ----
    UUID accountId = input.paymentAccountId();
    String accountName = blankToNull(input.paymentAccountName());
    String accountCurrency = blankToNull(input.paymentAccountCurrency());
    if (accountId != null && accountName == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指定付款账户时必须填写账户名称");
    }
    if (accountCurrency != null && !accountCurrency.equals(currency)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "付款账户币种 " + accountCurrency + " 与付款单币种 " + currency + " 不一致");
    }

    // ---- 分批安排 ----
    var installmentInputs =
        input.installments() == null ? List.<FinancePaymentOrderInstallmentInput>of() : input.installments();
    var installments = new ArrayList<InstallmentDraft>();
    var installmentSum = BigDecimal.ZERO;
    int seq = 1;
    for (var item : installmentInputs) {
      if (item.plannedPaidDate() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分批必须填写计划付款日");
      }
      var itemCurrency = requireCurrency(item.currency(), "分批币种");
      if (!itemCurrency.equals(currency)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "分批币种 " + itemCurrency + " 与付款单币种 " + currency + " 不一致");
      }
      var itemAmount = money(item.amount(), "分批金额");
      if (itemAmount.signum() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分批金额必须大于 0");
      }
      installments.add(new InstallmentDraft(seq++, item.plannedPaidDate(), itemCurrency, itemAmount));
      installmentSum = installmentSum.add(itemAmount);
    }
    if (!installments.isEmpty() && installmentSum.compareTo(amount) != 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "分批合计 " + installmentSum + " 与单头金额 " + amount + " 不一致：分批必须正好分完这笔钱");
    }

    return new DraftOrder(
        first.counterpartyType(),
        first.counterpartyRef(),
        blankToNull(input.counterpartyName()) == null ? first.counterpartyName() : input.counterpartyName().trim(),
        currency,
        amount,
        baseCurrency,
        rate,
        input.paymentMethod(),
        accountId,
        accountName == null ? "" : accountName,
        accountCurrency,
        planId,
        planNo,
        input.note() == null ? "" : input.note().trim(),
        facts,
        installments);
  }

  /** 读一条应付明细的事实（**只读** 12.1.2.1）。 */
  private PayableLineFact payableLineFact(UUID payableLineId) {
    var row =
        sql.fetchOne(
            "select l.id, l.payable_id, l.line_no, l.description, l.sku_code, p.currency,"
                + " l.amount, l.paid_amount, p.payable_no, p.due_date, p.status,"
                + " p.counterparty_type, p.counterparty_ref, p.counterparty_name"
                + " from finance_payable_line l"
                + " join finance_payable p on p.tenant_id = l.tenant_id and p.id = l.payable_id"
                + " where l.id = ?",
            payableLineId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "应付明细行不存在（" + payableLineId + "）：请从应付单里重新选择");
    }
    return new PayableLineFact(
        row.get("id", UUID.class),
        row.get("payable_id", UUID.class),
        row.get("payable_no", String.class),
        row.get("line_no", Integer.class),
        row.get("description", String.class),
        row.get("sku_code", String.class),
        row.get("currency", String.class),
        row.get("amount", BigDecimal.class),
        row.get("paid_amount", BigDecimal.class),
        row.get("due_date", LocalDate.class),
        row.get("status", String.class),
        PaymentCounterpartyType.valueOf(row.get("counterparty_type", String.class)),
        row.get("counterparty_ref", UUID.class),
        row.get("counterparty_name", String.class));
  }

  // ================================================================ 提交审批

  /**
   * 提交审批（**草稿 → 待审**）。
   *
   * <p>🔴 <b>真正的审批判定在 identity 侧</b>：本方法只做「查生效流程 + 提交实例」，
   * 起始节点（金额阈值命中哪个分支）是 identity 自己选的。
   *
   * @param bearer 调用方自己的会话（转发给 identity，租户由那边按成员关系解析）
   */
  @Transactional
  public FinancePaymentOrderSubmitResult submit(
      UUID id, AccessService.Access actor, String bearer) {
    var order = requireOrder(id);
    var status = order.get("status", String.class);
    if (!"DRAFT".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有草稿态的付款单可以提交审批（当前：" + status + "）");
    }
    var flow = approvals.activeFlow(ApprovalPort.DOCUMENT_TYPE_CODE, bearer);
    var flowId = flow.flow().id();
    if (flowId == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "审批流没有 id，无法提交：请在 17.4.1.1 检查流程定义");
    }
    var amount = order.get("amount", BigDecimal.class);
    var currency = order.get("currency", String.class);
    var subject = order.get("order_no", String.class) + " · " + order.get("counterparty_name", String.class);
    var submitted =
        approvals.submit(
            flowId,
            APPROVAL_SOURCE_SYSTEM,
            order.get("id", UUID.class).toString(),
            subject,
            currency,
            amount,
            bearer);

    var at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_payment_order set status='PENDING_APPROVAL', approval_status='PENDING',"
            + " approval_flow_id=?, approval_flow_name=?, approval_instance_id=?, approval_instance_no=?,"
            + " approval_current_node_key=?, approval_current_node_name=?, approval_routed_by=?,"
            + " submitted_by=?, submitted_at=?::timestamptz, version=version+1, updated_at=?::timestamptz"
            + " where id=?",
        flowId,
        flow.flow().flowName() == null ? "" : flow.flow().flowName(),
        submitted.id(),
        submitted.instanceNo() == null ? "" : submitted.instanceNo(),
        submitted.currentNodeKey() == null ? "" : submitted.currentNodeKey(),
        submitted.currentNodeName() == null ? "" : submitted.currentNodeName(),
        submitted.routedBy() == null ? "" : submitted.routedBy(),
        actor.membershipId(),
        at,
        at,
        id);

    // 提交那一刻也留一条审批记录（本地写的，不是镜像来的）。
    sql.execute(
        "insert into finance_payment_approval_record(id,tenant_id,order_id,approval_instance_id,"
            + " approval_instance_no,action,node_key,node_name,node_order,actor_id,actor_name,comment,"
            + " decided_at,dwell_seconds,source_system,synced_at)"
            + " values(?,?,?,?,?,'SUBMIT',?,?,0,?,?,'提交审批',?::timestamptz,0,'finance',?::timestamptz)",
        UUID.randomUUID(),
        actor.tenantId(),
        id,
        submitted.id(),
        submitted.instanceNo() == null ? "" : submitted.instanceNo(),
        submitted.currentNodeKey() == null ? "" : submitted.currentNodeKey(),
        submitted.currentNodeName() == null ? "" : submitted.currentNodeName(),
        actor.userId(),
        "",
        at,
        at);
    appendEvent(id, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", "提交审批", actor.userId(), at);

    var updated = orderRow(requireOrder(id));
    return new FinancePaymentOrderSubmitResult(
        updated,
        submitted.id(),
        submitted.instanceNo(),
        submitted.currentNodeKey(),
        submitted.currentNodeName(),
        submitted.routedBy(),
        "已提交审批，当前停在节点「" + submitted.currentNodeName() + "」（路由依据：" + routedByLabel(submitted.routedBy()) + "）");
  }

  // ================================================================ 同步审批结果

  /**
   * 同步审批结果（**读回 identity 的链路**）。
   *
   * <p>三种结局：
   * <ul>
   *   <li>还在审（PENDING）⇒ 刷新当前节点，记一条同步事件，状态不动；
   *   <li>已批（APPROVED）⇒ 待审 → 已批，记审批人与审批时刻；
   *   <li>已驳回（REJECTED）⇒ <b>待审 → 草稿</b>（可改可再提交），驳回意见进审批记录与事件流。
   * </ul>
   */
  @Transactional
  public FinancePaymentOrderSyncResult sync(UUID id, AccessService.Access actor, String bearer) {
    var order = requireOrder(id);
    var status = order.get("status", String.class);
    if ("DRAFT".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "草稿态的付款单还没有提交审批，没有可同步的审批结果");
    }
    var instanceId = order.get("approval_instance_id", UUID.class);
    if (instanceId == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这张付款单没有审批实例");
    }
    var detail = approvals.record(instanceId, bearer);
    var instance = detail.instance();
    var timeline = detail.timeline() == null ? List.<ApprovalPort.ActionView>of() : detail.timeline();

    // ---- 镜像新增的动作（按 action_id 去重 ⇒ 反复同步不灌重复行） ----
    int mirrored = 0;
    for (var action : timeline) {
      if (action.actionId() == null) continue;
      var exists =
          sql.fetchOne(
                  "select 1 from finance_payment_approval_record"
                      + " where approval_instance_id = ? and action_id = ?",
                  instanceId,
                  action.actionId())
              != null;
      if (exists) continue;
      var at = action.decidedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC)
          : ApprovalPort.toOffset(action.decidedAt());
      sql.execute(
          "insert into finance_payment_approval_record(id,tenant_id,order_id,approval_instance_id,"
              + " approval_instance_no,action_id,action,node_key,node_name,node_order,actor_id,actor_name,"
              + " comment,decided_at,dwell_seconds,source_system,synced_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,'finance',?::timestamptz)",
          UUID.randomUUID(),
          actor.tenantId(),
          id,
          instanceId,
          instance.instanceNo() == null ? "" : instance.instanceNo(),
          action.actionId(),
          normalizeAction(action.action()),
          action.nodeKey() == null ? "" : action.nodeKey(),
          action.nodeName() == null ? "" : action.nodeName(),
          action.nodeOrder(),
          action.actorId(),
          action.actorName() == null ? "" : action.actorName(),
          action.comment() == null ? "" : action.comment(),
          at,
          action.dwellSeconds(),
          at);
      mirrored++;
    }

    var instanceStatus = instance.status() == null ? "PENDING" : instance.status();
    var at = OffsetDateTime.now(ZoneOffset.UTC);
    var message = "审批结果已同步";

    if ("APPROVED".equals(instanceStatus)) {
      var approver = lastActor(timeline, "APPROVE");
      var decidedAt =
          instance.finishedAt() == null ? at : ApprovalPort.toOffset(instance.finishedAt());
      sql.execute(
          "update finance_payment_order set status='APPROVED', approval_status='APPROVED',"
              + " approved_by=?, approved_at=?::timestamptz, approval_current_node_key=?,"
              + " approval_current_node_name=?, version=version+1, updated_at=?::timestamptz"
              + " where id=? and status='PENDING_APPROVAL'",
          approver,
          decidedAt,
          instance.currentNodeKey() == null ? "" : instance.currentNodeKey(),
          instance.currentNodeName() == null ? "" : instance.currentNodeName(),
          at,
          id);
      appendEvent(id, "APPROVED", "PENDING_APPROVAL", "APPROVED", "审批通过", approver, at);
      message = "审批已通过：付款单进入「已批」，等待 12.2.2.2 执行付款";
    } else if ("REJECTED".equals(instanceStatus)) {
      var rejecter = lastActor(timeline, "REJECT");
      var reason = lastComment(timeline, "REJECT");
      sql.execute(
          "update finance_payment_order set status='DRAFT', approval_status='REJECTED',"
              + " approved_by=null, approved_at=null, version=version+1, updated_at=?::timestamptz"
              + " where id=? and status='PENDING_APPROVAL'",
          at,
          id);
      appendEvent(id, "RETURNED_TO_DRAFT", "PENDING_APPROVAL", "DRAFT", reason, rejecter, at);
      message = "审批被驳回：付款单已退回草稿，改完可以重新提交";
    } else {
      sql.execute(
          "update finance_payment_order set approval_current_node_key=?, approval_current_node_name=?,"
              + " updated_at=?::timestamptz where id=?",
          instance.currentNodeKey() == null ? "" : instance.currentNodeKey(),
          instance.currentNodeName() == null ? "" : instance.currentNodeName(),
          at,
          id);
      appendEvent(id, "APPROVAL_SYNCED", status, status, "同步审批进度", actor.userId(), at);
      message = "审批进行中，当前停在节点「" + instance.currentNodeName() + "」";
    }

    var updated = orderRow(requireOrder(id));
    var approvalStatus =
        updated.approvalStatus() == null ? ApprovalStatus.PENDING : updated.approvalStatus();
    return new FinancePaymentOrderSyncResult(updated, mirrored, approvalStatus, message);
  }

  /** identity 的动作词表 → 本模块的动作词表（未知动作不丢，归成原始值）。 */
  private String normalizeAction(String action) {
    if (action == null) return "APPROVE";
    var upper = action.trim().toUpperCase(java.util.Locale.ROOT);
    return switch (upper) {
      case "APPROVE", "REJECT", "TRANSFER" -> upper;
      default -> upper;
    };
  }

  /** 链路上最后一个某类动作的操作人。 */
  private UUID lastActor(List<ApprovalPort.ActionView> timeline, String action) {
    for (int i = timeline.size() - 1; i >= 0; i--) {
      var item = timeline.get(i);
      if (action.equalsIgnoreCase(item.action()) && item.actorId() != null) {
        return item.actorId();
      }
    }
    return null;
  }

  /** 链路上最后一个某类动作的意见。 */
  private String lastComment(List<ApprovalPort.ActionView> timeline, String action) {
    for (int i = timeline.size() - 1; i >= 0; i--) {
      var item = timeline.get(i);
      if (action.equalsIgnoreCase(item.action())) {
        var comment = item.comment();
        if (comment != null && !comment.isBlank()) return comment.trim();
      }
    }
    return "审批被驳回";
  }

  // ================================================================ 取数 / 映射

  private Record requireOrder(UUID id) {
    var row = sql.fetchOne("select * from finance_payment_order where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "付款单不存在");
    }
    return row;
  }

  private FinancePaymentOrderRow orderRow(Record r) {
    return new FinancePaymentOrderRow(
        r.get("id", UUID.class),
        r.get("order_no", String.class),
        PaymentCounterpartyType.valueOf(r.get("counterparty_type", String.class)),
        r.get("counterparty_ref", UUID.class),
        r.get("counterparty_name", String.class),
        r.get("currency", String.class),
        r.get("amount", BigDecimal.class),
        r.get("base_currency", String.class),
        r.get("exchange_rate", BigDecimal.class),
        r.get("base_amount", BigDecimal.class),
        PaymentMethod.valueOf(r.get("payment_method", String.class)),
        r.get("payment_account_id", UUID.class),
        r.get("payment_account_name", String.class),
        r.get("payment_account_currency", String.class),
        r.get("payment_account_owner_module", String.class),
        r.get("payment_plan_id", UUID.class),
        r.get("payment_plan_no", String.class),
        PaymentOrderStatus.valueOf(r.get("status", String.class)),
        r.get("execution_owner_module", String.class),
        r.get("execution_available", Boolean.class),
        ApprovalStatus.valueOf(r.get("approval_status", String.class)),
        r.get("approval_flow_id", UUID.class),
        r.get("approval_flow_name", String.class),
        r.get("approval_instance_id", UUID.class),
        r.get("approval_instance_no", String.class),
        r.get("approval_current_node_key", String.class),
        r.get("approval_current_node_name", String.class),
        r.get("approval_routed_by", String.class),
        r.get("submitted_by", UUID.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("approved_by", UUID.class),
        r.get("approved_at", OffsetDateTime.class),
        r.get("failure_reason", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private List<FinancePaymentOrderLineRow> lines(UUID orderId) {
    return sql.fetch(
            "select l.*, pl.payable_id, p.payable_no from finance_payment_order_line l"
                + " join finance_payable_line pl on pl.tenant_id = l.tenant_id and pl.id = l.payable_line_id"
                + " join finance_payable p on p.tenant_id = pl.tenant_id and p.id = pl.payable_id"
                + " where l.order_id = ? order by l.line_no",
            orderId)
        .map(
            r ->
                new FinancePaymentOrderLineRow(
                    r.get("id", UUID.class),
                    r.get("line_no", Integer.class),
                    r.get("payable_line_id", UUID.class),
                    r.get("payable_id", UUID.class),
                    r.get("payable_no", String.class),
                    r.get("description", String.class),
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("payable_amount_snapshot", BigDecimal.class),
                    r.get("payable_paid_snapshot", BigDecimal.class),
                    r.get("active", Boolean.class),
                    r.get("created_at", OffsetDateTime.class)));
  }

  private List<FinancePaymentOrderInstallmentRow> installments(UUID orderId) {
    return sql.fetch(
            "select * from finance_payment_order_installment where order_id = ? order by seq", orderId)
        .map(
            r ->
                new FinancePaymentOrderInstallmentRow(
                    r.get("id", UUID.class),
                    r.get("seq", Integer.class),
                    r.get("planned_paid_date", LocalDate.class),
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    InstallmentStatus.valueOf(r.get("status", String.class)),
                    r.get("execution_owner_module", String.class),
                    r.get("execution_available", Boolean.class),
                    r.get("failure_reason", String.class),
                    r.get("created_at", OffsetDateTime.class)));
  }

  private List<FinancePaymentOrderEventRow> events(UUID orderId) {
    return sql.fetch(
            "select * from finance_payment_order_event where order_id = ? order by occurred_at, id",
            orderId)
        .map(
            r ->
                new FinancePaymentOrderEventRow(
                    r.get("id", UUID.class),
                    r.get("event_type", String.class),
                    r.get("from_status", String.class),
                    r.get("to_status", String.class),
                    r.get("reason", String.class),
                    r.get("actor_id", UUID.class),
                    r.get("occurred_at", OffsetDateTime.class)));
  }

  private List<FinancePaymentApprovalRecordRow> approvalRecords(UUID orderId) {
    return sql.fetch(
            "select * from finance_payment_approval_record where order_id = ? order by decided_at, id",
            orderId)
        .map(
            r ->
                new FinancePaymentApprovalRecordRow(
                    r.get("id", UUID.class),
                    r.get("approval_instance_id", UUID.class),
                    r.get("approval_instance_no", String.class),
                    r.get("action_id", UUID.class),
                    r.get("action", String.class),
                    r.get("node_key", String.class),
                    r.get("node_name", String.class),
                    r.get("node_order", Integer.class),
                    r.get("actor_id", UUID.class),
                    r.get("actor_name", String.class),
                    r.get("comment", String.class),
                    r.get("decided_at", OffsetDateTime.class),
                    r.get("dwell_seconds", Long.class),
                    r.get("source_system", String.class),
                    r.get("synced_at", OffsetDateTime.class)));
  }

  // ================================================================ 工具

  /** 追加一条事件（append-only）。 */
  private void appendEvent(
      UUID orderId, String type, String from, String to, String reason, UUID actorId, OffsetDateTime at) {
    sql.execute(
        "insert into finance_payment_order_event(id,tenant_id,order_id,event_type,from_status,"
            + " to_status,reason,actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenantId(),
        orderId,
        type,
        from == null ? "" : from,
        to == null ? "" : to,
        reason == null ? "" : reason,
        actorId,
        at);
  }

  /** 当前事务里的租户 id（来自 RLS 上下文，**不是**客户端自称的）。 */
  private UUID currentTenantId() {
    var row = sql.fetchOne("select current_setting('app.current_tenant', true)::uuid as t");
    return row == null ? null : row.get("t", UUID.class);
  }

  /** 单号：前缀 + 12 位十六进制（大写）。 */
  private static String nextReference(String prefix) {
    return prefix
        + "-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  /** 金额规整（4 位小数，HALF_UP）。 */
  private static BigDecimal money(BigDecimal value, String label) {
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
    }
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 币种规整：三位大写字母。 */
  private static String requireCurrency(String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
    }
    var value = raw.trim().toUpperCase(java.util.Locale.ROOT);
    if (!value.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "必须是三位大写币种代码（当前：" + raw + "）");
    }
    return value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** 路由依据的可读名。 */
  static String routedByLabel(String routedBy) {
    if (routedBy == null) return "";
    return switch (routedBy) {
      case "AMOUNT_BRANCH" -> "命中金额阈值分支";
      case "FLOW_ORDER" -> "流程顺序默认";
      default -> routedBy;
    };
  }

  /** 拼参数（注意顺序：where 条件在前，limit/offset 在后）。 */
  private static Object[] concat(List<Object> binds, Object... tail) {
    var all = new ArrayList<>(binds);
    all.addAll(java.util.Arrays.asList(tail));
    return all.toArray();
  }
}
