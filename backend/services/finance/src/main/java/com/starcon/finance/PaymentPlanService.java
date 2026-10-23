package com.starcon.finance;

import static com.starcon.finance.PaymentPlanModels.*;

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
 * 12.1.2.2 付款计划。
 *
 * <p>蓝图 12-finance.md：「按付款条件（账期、里程碑、到期日）生成付款计划与到期提醒，
 * 支持按供应商与到期窗口批量安排付款。」输出：「付款计划表 + 到期提醒 + 资金需求预测。」
 * 加粗句：「<b>付款计划与资金预测联动</b>：跨境资金常有回款延迟与付款刚性，错配会导致现金流断裂。」
 *
 * <p><b>本模块的四条设计主线</b>：
 *
 * <ol>
 *   <li><b>三种付款条件是三种算法，不是一个 type 字段上的三行取值。</b>
 *       <ul>
 *         <li>{@code DUE_DATE}（到期日）—— 条件日 = 应付单自带的到期日（12.1.2.1 的事实）。
 *         <li>{@code TERM}（账期）—— 条件日 = 起算基准日 + <b>账期规则</b>的天数。
 *             账期规则是租户自己维护的一张表，<b>系统不预置任何一行</b>：
 *             预置一条「30 天账期」等于替用户把资金政策定了。没有可用规则 ⇒ 排不出计划。
 *         <li>{@code MILESTONE}（里程碑）—— 一张应付拆成 <b>多条</b>计划，
 *             每个里程碑一条，按比例拆金额。
 *       </ul>
 *   <li><b>付款执行不可用 —— 数据层就不存在「已付款」。</b>
 *       本模块<b>绝不</b>造付款单、<b>绝不</b>写「已付款」：付款的唯一写入口是 12.1.2.1 的
 *       行级 {@code paid_amount}，归 <b>12.2.2.1</b>（未交付）。
 *       计划与批次上的 {@code execution_available} 由表级 CHECK 钉死为 false，
 *       {@code execution_owner_module} 恒为 {@code 12.2.2.1}。
 *   <li><b>提醒记录归本模块，推送通道不归。</b>
 *       本模块提供<b>真实的提醒条目与真实的到期判定</b>（UPCOMING / DUE / OVERDUE）
 *       与<b>真实的处理状态</b>（PENDING / ACKNOWLEDGED / DISMISSED）；
 *       但<b>不推送</b>：{@code push_status} 恒为 {@code NOT_PUSHED}，
 *       {@code push_owner_module} 恒为 {@code 1.3}（未交付）。
 *       没有 sent_at、没有「已发送」这个取值、没有假的通知计数。
 *   <li><b>预测是实时聚合出来的，不是另一张静态表。</b>
 *       资金需求预测按 (币种 × 到期窗口) 对<b>付款计划</b>（流出）
 *       与<b>12.1.1.1 应收单</b>的未核销余额（流入）做聚合 ⇒
 *       <b>计划改了，预测当场跟着变</b>，不需要任何同步动作。
 * </ol>
 *
 * <p><b>跨模块边界</b>：本模块与上游 12.1.2.1 在<b>同一个服务</b>里 ⇒ 读上游表走 jOOQ 显式 SQL。
 * 但对上游表<b>只读</b>：不写、不改结构、不加触发器。
 * 12.1.1.1 的应收单同理（预测的流入侧只读它）。
 */
@Service
public class PaymentPlanService {

  private static final Logger logger = LoggerFactory.getLogger(PaymentPlanService.class);

  /** 当前 UTC 日历日：提醒判定与预测窗口的基准日（与 12.1.2.1 同一口径）。 */
  static final LocalDate TODAY_UTC = LocalDate.now(ZoneOffset.UTC);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 提醒默认视野天数：计划付款日在这一天之后的，暂时不进提醒清单。 */
  static final int DEFAULT_REMINDER_HORIZON_DAYS = 14;

  /** 预测默认窗口天数。 */
  static final int DEFAULT_FORECAST_WINDOW_DAYS = 7;

  /** 预测默认覆盖天数。 */
  static final int DEFAULT_FORECAST_HORIZON_DAYS = 90;

  /** 提醒被移出视野时的**系统**原因（用它区分「系统收起来了」与「人手动忽略了」）。 */
  static final String REMINDER_OUT_OF_WINDOW = "已移出提醒窗口";

  private final DSLContext sql;

  public PaymentPlanService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 内部结构

  /** 一张应付的取数快照（只读上游，不写）。 */
  private record PayableFact(
      UUID id,
      String no,
      LocalDate dueDate,
      LocalDate confirmedDate,
      String currency,
      BigDecimal unpaid,
      String counterpartyType,
      UUID counterpartyRef,
      String counterpartyName) {}

  /** 一条待落库的计划（算好了、还没写）。 */
  private record DraftPlan(
      UUID payableId,
      PaymentCondition condition,
      UUID termRuleId,
      UUID milestoneId,
      String milestoneName,
      LocalDate conditionDate,
      String currency,
      BigDecimal plannedAmount,
      BigDecimal unpaidSnapshot) {}

  /** 账期规则（内部）。 */
  private record TermRule(UUID id, String name, TermBaseKind baseKind, int termDays, String currency) {}

  /** 里程碑规则（内部）。 */
  private record MilestoneRule(UUID id, String name, String currency, List<MilestoneFact> milestones) {}

  /** 里程碑（内部）。 */
  private record MilestoneFact(UUID id, int seq, String name, Integer offsetDays, LocalDate triggerDate, BigDecimal ratio) {}

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 页面必须能分开两件事：<b>「上游未交付」</b>（付款执行、提醒推送）
   * 与<b>「本模块已经做完」</b>（计划、安排、判定、预测）。
   */
  public FinancePaymentPlanCapabilities capabilities() {
    var capabilities = new ArrayList<FinancePaymentPlanCapability>();
    var gaps = new ArrayList<FinancePaymentPlanDownstreamGap>();

    capabilities.add(
        new FinancePaymentPlanCapability(
            "12.1.2.2",
            "付款计划表",
            true,
            "finance_payment_plan / finance_payment_batch / finance_payment_plan_event",
            "",
            "计划表已落地：数据落 PostgreSQL 16，查询走 jOOQ 显式 SQL，全链路 BigDecimal + NUMERIC，无浮点。"
                + "计划与批次上的 execution_available 由**表级 CHECK 钉死为 false** ⇒ "
                + "**数据层就不存在「已付款」这个状态**。"));
    capabilities.add(
        new FinancePaymentPlanCapability(
            "12.1.2.2",
            "三种付款条件（账期 / 里程碑 / 到期日）",
            true,
            "finance_payment_term_rule · finance_payment_milestone_rule · finance_payment_milestone · 计划上的 payment_condition",
            "",
            "三种条件是**三种算法**，不是同一个 type 字段上的三行取值："
                + "到期日 = 直接取应付单的到期日（12.1.2.1 的事实）；"
                + "账期 = 起算基准日 + **租户自己维护的账期规则**（系统不预置任何一行）；"
                + "里程碑 = 一张应付拆成多条计划，按比例拆金额。"));
    capabilities.add(
        new FinancePaymentPlanCapability(
            "12.1.2.2",
            "按供应商与到期窗口批量安排付款",
            true,
            "finance_payment_batch（每批 = 一个供应商 + 一个到期窗口 + 一个付款窗口 + 一个币种）",
            "",
            "安排是真的批量落库：一次请求扫一批应付、**按币种分批**、逐条写计划并挂到批次上，"
                + "批次的金额与笔数由 DB 触发器从计划派生。"
                + "已经排过的同一张应付**不会**被排第二次（部分唯一索引在数据层挡着）。"));
    capabilities.add(
        new FinancePaymentPlanCapability(
            "12.1.2.2",
            "到期提醒（记录与判定）",
            true,
            "finance_payment_reminder",
            "",
            "提醒条目、到期判定（即将到期 / 今日到期 / 已逾期）与处理状态（待处理 / 已确认 / 已忽略）"
                + "都是**真的**：扫描时按计划付款日重算，状态变了就重置为「待处理」，"
                + "已经人工确认过的**不会**被重置。"
                + "**但本模块不推送**（见 1.3 那一条）。"));
    capabilities.add(
        new FinancePaymentPlanCapability(
            "12.1.2.2",
            "资金需求预测（与计划联动）",
            true,
            "按 (币种 × 到期窗口) 对 finance_payment_plan 与 finance_receivable 实时聚合",
            "",
            "预测**不是一张静态表**：它是每次查询时按付款计划（流出）与 12.1.1.1 应收单的"
                + "未核销余额（流入）实时聚合出来的 ⇒ **计划改了，预测当场跟着变**。"
                + "净额 = 流入 − 流出，负数即「这个窗口净流出」⇒ 现金流断裂风险。"
                + "**绝不跨币种相加**：流入与流出都按币种分开比。"));
    capabilities.add(
        new FinancePaymentPlanCapability(
            "12.2.2.1",
            "付款执行（把计划变成真的付款）",
            false,
            "写路径未开放：execution_available 由表级 CHECK 钉死为 false",
            "12.2.2.1",
            "蓝图关系写死「下游 = 12.2.2.1」。本模块交付的是**计划与安排**，"
                + "**不产生任何付款动作**：不造付款单、不写「已付款」、没有付款回执。"
                + "付款的唯一写入口是 12.1.2.1 的行级 paid_amount，归 12.2.2.1。"
                + "12.2.2.1 交付后读本模块的批次与计划建付款单即可，**不需要**在本表上再加列。"));
    capabilities.add(
        new FinancePaymentPlanCapability(
            "1.3",
            "提醒推送（站内信 / 邮件 / IM）",
            false,
            "推送通道未交付：push_status 恒为 NOT_PUSHED（表级 CHECK）",
            "1.3",
            "本模块交付的是**真实的提醒条目与真实的到期判定**，"
                + "但**不往外推送**：push_status 恒为 'NOT_PUSHED'、push_owner_module 恒为 '1.3'，"
                + "**没有** sent_at、**没有**「已发送」这个取值、**没有**假的通知计数。"
                + "1.3 交付后由它消费本表（或经授权 API 读取），本表结构不用改。"));
    capabilities.add(
        new FinancePaymentPlanCapability(
            "12.7",
            "资金账户头寸",
            false,
            "本模块只做**需求侧**预测",
            "12.7",
            "蓝图 12.7 才是资金账户与头寸的归属。本模块的预测是**付款需求侧**的，"
                + "不含账户余额；真正的「够不够付」要等 12.7 的资金头寸落地后才对得上。"));

    gaps.add(
        new FinancePaymentPlanDownstreamGap(
            "12.2.2.1",
            "付款单与审批",
            "finance",
            false,
            "12.2 收付款",
            "本模块只排计划、不付款：付款执行未交付，execution_available 恒为 false。"));
    gaps.add(
        new FinancePaymentPlanDownstreamGap(
            "1.3",
            "提醒系统（推送通道）",
            "notification",
            false,
            "01 工作台与门户",
            "提醒记录与到期判定已交付；**推送**（站内信 / 邮件 / IM）未交付。"));
    gaps.add(
        new FinancePaymentPlanDownstreamGap(
            "12.7",
            "资金账户与头寸",
            "finance",
            false,
            "12.7 资金",
            "需求侧预测已交付；账户余额与头寸未交付 ⇒ 还答不了「够不够付」。"));
    gaps.add(
        new FinancePaymentPlanDownstreamGap(
            "12.1.2.3",
            "供应商对账（财务侧）",
            "finance",
            false,
            "12.1 应收与资金",
            "财务侧核对与最终确认未交付 ⇒ 供应商对账单来源的应付当前不可产生。"));

    return new FinancePaymentPlanCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinancePaymentPlanOptions options() {
    var currencies =
        sql.fetch("select distinct currency from finance_payment_plan order by currency")
            .map(r -> r.get(0, String.class));
    var statuses =
        sql.fetch("select distinct status from finance_payment_plan order by status")
            .map(r -> r.get(0, String.class));
    var conditions =
        sql.fetch("select distinct payment_condition from finance_payment_plan order by payment_condition")
            .map(r -> r.get(0, String.class));
    var counterparties =
        sql.fetch(
                "select distinct pa.counterparty_ref, pa.counterparty_name from finance_payment_plan p"
                    + " join finance_payable pa on pa.tenant_id = p.tenant_id and pa.id = p.payable_id"
                    + " where pa.counterparty_ref is not null"
                    + " order by pa.counterparty_name, pa.counterparty_ref")
            .map(
                r ->
                    new FinancePaymentPlanOptionRef(
                        r.get(0, UUID.class).toString(), r.get(1, String.class)));
    var termRules =
        sql.fetch("select id, name from finance_payment_term_rule order by name")
            .map(r -> new FinancePaymentPlanOptionRef(r.get(0, UUID.class).toString(), r.get(1, String.class)));
    var milestoneRules =
        sql.fetch("select id, name from finance_payment_milestone_rule order by name")
            .map(
                r -> new FinancePaymentPlanOptionRef(r.get(0, UUID.class).toString(), r.get(1, String.class)));
    return new FinancePaymentPlanOptions(
        currencies, statuses, conditions, counterparties, termRules, milestoneRules);
  }

  // ================================================================ 列表

  /**
   * 付款计划列表：真分页 + 真筛选 + 真排序。
   *
   * <p>⚠️ **汇总覆盖的是整个筛选结果集，不是当前页**：分页一变汇总就变的话，
   * 「翻到第二页看到总额变小了」会被读成「数据丢了」。
   */
  public FinancePaymentPlanPage page(PaymentPlanQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from finance_payment_plan p"
                    // ⚠️ 筛选条件里有 pa.* 的列（供应商筛选）⇒ 计数查询也必须带上同样的 JOIN。
                    + " join finance_payable pa on pa.tenant_id = p.tenant_id and pa.id = p.payable_id"
                    + clause,
                binds.toArray())
            .get(0, Long.class);
    List<FinancePaymentPlanRow> items =
        sql.fetch(
                "select p.*, pa.payable_no, pa.due_date, pa.counterparty_type, pa.counterparty_ref,"
                    + " pa.counterparty_name, pa.unpaid_amount as current_unpaid, b.batch_no,"
                    + " ms.name as milestone_name"
                    + " from finance_payment_plan p"
                    + " join finance_payable pa on pa.tenant_id = p.tenant_id and pa.id = p.payable_id"
                    + " left join finance_payment_batch b on b.tenant_id = p.tenant_id and b.id = p.batch_id"
                    + " left join finance_payment_milestone ms on ms.tenant_id = p.tenant_id"
                    + "   and ms.id = p.milestone_id"
                    + clause
                    + " order by p.planned_paid_date, p.id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::planRow);

    var summaries = summaryByCurrency(where, binds);
    long overdueCount =
        summaries.stream().mapToLong(FinancePaymentPlanCurrencySummary::overdueCount).sum();
    return new FinancePaymentPlanPage(items, p, s, total, summaries, overdueCount);
  }

  /**
   * 按币种的汇总。
   *
   * <p>⚠️ **已取消的计划不计入**：取消的计划在资金口径上不存在，
   * 把它算进「要付多少钱」会让总额虚高。
   */
  private List<FinancePaymentPlanCurrencySummary> summaryByCurrency(
      List<String> where, List<Object> binds) {
    var clauses = new ArrayList<String>();
    clauses.add("p.status <> 'CANCELLED'");
    clauses.addAll(where);
    return sql.fetch(
            "select p.currency,"
                + " sum(p.planned_amount) as planned_amount,"
                + " count(*) as cnt,"
                + " count(*) filter (where p.status = 'SCHEDULED') as scheduled_count,"
                + " count(*) filter (where p.planned_paid_date < (now() at time zone 'UTC')::date) as overdue_count"
                + " from finance_payment_plan p"
                // ⚠️ 筛选条件里有 pa.* 的列（供应商筛选）⇒ 汇总查询也必须带上同样的 JOIN，
                // 否则「按供应商筛选」会直接把汇总查询打成 SQL 错误。
                + " join finance_payable pa on pa.tenant_id = p.tenant_id and pa.id = p.payable_id"
                + " where "
                + String.join(" and ", clauses)
                + " group by p.currency order by p.currency",
            binds.toArray())
        .map(
            r ->
                new FinancePaymentPlanCurrencySummary(
                    r.get("currency", String.class),
                    r.get("planned_amount", BigDecimal.class),
                    r.get("cnt", Long.class),
                    r.get("scheduled_count", Long.class),
                    r.get("overdue_count", Long.class)));
  }

  private void applyFilters(PaymentPlanQuery q, List<String> where, List<Object> binds) {
    if (q.status() != null) {
      where.add("p.status = ?");
      binds.add(q.status().name());
    }
    if (q.paymentCondition() != null) {
      where.add("p.payment_condition = ?");
      binds.add(q.paymentCondition().name());
    }
    if (q.currency() != null && !q.currency().isBlank()) {
      where.add("p.currency = ?");
      binds.add(q.currency());
    }
    if (q.counterpartyRef() != null) {
      where.add("pa.counterparty_ref = ?");
      binds.add(q.counterpartyRef());
    }
    if (q.batchId() != null) {
      where.add("p.batch_id = ?");
      binds.add(q.batchId());
    }
    if (q.overdueOnly() != null && q.overdueOnly()) {
      where.add("p.planned_paid_date < (now() at time zone 'UTC')::date");
      where.add("p.status <> 'CANCELLED'");
    }
    if (q.paidFrom() != null) {
      where.add("p.planned_paid_date >= ?::date");
      binds.add(q.paidFrom());
    }
    if (q.paidTo() != null) {
      where.add("p.planned_paid_date <= ?::date");
      binds.add(q.paidTo());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add(
          "(p.plan_no ilike ? or pa.payable_no ilike ? or pa.counterparty_name ilike ?"
              + " or p.note ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  // ================================================================ 详情

  /** 付款计划详情：计划 + 事件流 + 当前提醒。 */
  public FinancePaymentPlanDetail detail(UUID id) {
    var plan =
        sql.fetchOne(
            "select p.*, pa.payable_no, pa.due_date, pa.counterparty_type, pa.counterparty_ref,"
                + " pa.counterparty_name, pa.unpaid_amount as current_unpaid, b.batch_no,"
                + " ms.name as milestone_name"
                + " from finance_payment_plan p"
                + " join finance_payable pa on pa.tenant_id = p.tenant_id and pa.id = p.payable_id"
                + " left join finance_payment_batch b on b.tenant_id = p.tenant_id and b.id = p.batch_id"
                + " left join finance_payment_milestone ms on ms.tenant_id = p.tenant_id"
                + "   and ms.id = p.milestone_id"
                + " where p.id = ?",
            id);
    if (plan == null) throw notFound("付款计划 " + id + " 不存在或无权查看");
    var events =
        sql.fetch(
                "select * from finance_payment_plan_event where plan_id = ? order by occurred_at, id", id)
            .map(
                r ->
                    new FinancePaymentPlanEventRow(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("reason", String.class),
                        r.get("actor_id", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class)));
    var reminder =
        sql.fetchOne(
            "select r.*, p.plan_no, pa.payable_no, pa.counterparty_name"
                + " from finance_payment_reminder r"
                + " join finance_payment_plan p on p.tenant_id = r.tenant_id and p.id = r.plan_id"
                + " join finance_payable pa on pa.tenant_id = r.tenant_id and pa.id = r.payable_id"
                + " where r.plan_id = ?",
            id);
    return new FinancePaymentPlanDetail(
        planRow(plan), events, reminder == null ? null : reminderRow(reminder));
  }

  // ================================================================ 生成

  /**
   * 生成付款计划（**只生成，不排批次**）。
   *
   * <p>⚠️ **不产生任何付款动作**：本端点只产出计划行与到期判定。
   *
   * <p>⚠️ **已排过的同一张应付不会生成第二条**（数据层有部分唯一索引兜着），
   * 本层只是把它如实报成「跳过」，不装作排过了。
   */
  @Transactional
  public FinancePaymentPlanScheduleResult generate(
      FinancePaymentPlanGenerateInput input, AccessService.Access actor) {
    if (input.paymentCondition() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定付款条件（账期 / 里程碑 / 到期日）");
    }
    var warnings = new ArrayList<String>();
    var payables = candidatePayables(input.counterpartyRef(), input.dueFrom(), input.dueTo());
    var rows = new ArrayList<FinancePaymentPlanRow>();
    int planned = 0;
    int skipped = 0;

    for (var payable : payables) {
      var drafts = draftsFor(payable, input.paymentCondition(), input.termRuleId(), input.milestoneRuleId(), warnings);
      for (var draft : drafts) {
        if (activePlanOf(draft.payableId(), draft.milestoneId()) != null) {
          skipped++;
          continue;
        }
        var id = insertPlan(draft, draft.conditionDate(), null, PaymentPlanStatus.PLANNED, input.note(), actor);
        writeEvent(actor.tenantId(), id, "GENERATED", "", "PLANNED", input.note(), actor.userId());
        rows.add(planRow(planRecord(id)));
        planned++;
      }
    }
    if (!warnings.isEmpty()) {
      logger.warn("[finance] 生成付款计划有 {} 条未进入：{}", warnings.size(), warnings);
    }
    return new FinancePaymentPlanScheduleResult(
        payables.size(), planned, 0, skipped, 0, rows, List.of(), List.copyOf(warnings));
  }

  // ================================================================ 批量安排

  /**
   * 按供应商与到期窗口**批量安排付款**。
   *
   * <p>⚠️ **安排 ≠ 付款**：这里做的是「把某张应付排进某个付款窗口 / 批次」，
   * <b>不产生任何付款动作</b>（付款属 12.2.2.1，未交付）。
   *
   * <p>⚠️ **按币种分批**：跨币种的批次会让批次的「要付多少钱」变成一个没有意义的数字
   * （USD 与 EUR 加起来不是任何一个金额）⇒ 一个币种一批。
   *
   * <p>⚠️ **条件日可能被付款窗口压过**：条件日（到期日 / 基准日+账期 / 里程碑触发日）
   * 落在付款窗口之外时，计划日被压到窗口边界，并在计划上留下 {@code clamped} 标记
   * （生成列，不可随手改）。两个日期都保留在计划上，界面据此显示「按 X 算，压到 Y」。
   */
  @Transactional
  public FinancePaymentPlanScheduleResult schedule(
      FinancePaymentPlanScheduleInput input, AccessService.Access actor) {
    if (input.counterpartyRef() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批量安排必须指定供应商（按供应商排是这条能力的硬要求）");
    }
    if (input.dueFrom() == null || input.dueTo() == null || input.dueFrom().isAfter(input.dueTo())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "应付到期窗口不合法：起止日期都要填，且起日不能晚于止日");
    }
    if (input.paymentWindowStart() == null
        || input.paymentWindowEnd() == null
        || input.paymentWindowStart().isAfter(input.paymentWindowEnd())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "付款窗口不合法：起止日期都要填，且起日不能晚于止日");
    }
    if (input.paymentCondition() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定付款条件（账期 / 里程碑 / 到期日）");
    }

    var warnings = new ArrayList<String>();
    var payables =
        candidatePayables(input.counterpartyRef(), input.dueFrom(), input.dueTo());
    if (payables.isEmpty()) {
      warnings.add(
          "在到期窗口 "
              + input.dueFrom()
              + " ~ "
              + input.dueTo()
              + " 内没有找到该供应商的未付清应付 —— 可能是窗口太窄，或应付已付清 / 已作废");
    }

    // 按币种分批。
    var byCurrency = new LinkedHashMap<String, List<PayableFact>>();
    for (var payable : payables) {
      byCurrency.computeIfAbsent(payable.currency(), k -> new ArrayList<>()).add(payable);
    }

    var rows = new ArrayList<FinancePaymentPlanRow>();
    var batchIds = new ArrayList<UUID>();
    int planned = 0;
    int rearranged = 0;
    int skipped = 0;

    for (var entry : byCurrency.entrySet()) {
      String currency = entry.getKey();
      // 批次**按需建**：这个币种一笔都排不出来的时候不建空批次。
      UUID batchId = null;
      for (var payable : entry.getValue()) {
        var drafts =
            draftsFor(payable, input.paymentCondition(), input.termRuleId(), input.milestoneRuleId(), warnings);
        for (var draft : drafts) {
          var existing = activePlanOf(draft.payableId(), draft.milestoneId());
          if (existing != null) {
            var existingCondition = existing.get("payment_condition", String.class);
            if (!existingCondition.equals(draft.condition().name())) {
              skipped++;
              warnings.add(
                  "应付 "
                      + payable.no()
                      + " 已经按「"
                      + conditionLabel(existingCondition)
                      + "」排过计划，换条件请先取消那条计划");
              continue;
            }
          }
          LocalDate plannedDate =
              clamp(draft.conditionDate(), input.paymentWindowStart(), input.paymentWindowEnd());
          if (batchId == null) {
            batchId =
                insertBatch(
                    payable,
                    input.dueFrom(),
                    input.dueTo(),
                    input.paymentWindowStart(),
                    input.paymentWindowEnd(),
                    currency,
                    input.note(),
                    actor);
            batchIds.add(batchId);
          }
          if (existing == null) {
            var id =
                insertPlan(draft, plannedDate, batchId, PaymentPlanStatus.SCHEDULED, input.note(), actor);
            writeEvent(
                actor.tenantId(),
                id,
                "SCHEDULED",
                "",
                "SCHEDULED",
                "排入付款批次 " + input.paymentWindowStart() + " ~ " + input.paymentWindowEnd(),
                actor.userId());
            rows.add(planRow(planRecord(id)));
            planned++;
          } else {
            var id = existing.get("id", UUID.class);
            sql.execute(
                "update finance_payment_plan set planned_paid_date = ?::date, planned_amount = ?,"
                    + " unpaid_snapshot = ?, batch_id = ?, status = 'SCHEDULED', note = ?,"
                    + " updated_at = ?::timestamptz, version = version + 1"
                    + " where id = ?",
                plannedDate,
                draft.plannedAmount(),
                draft.unpaidSnapshot(),
                batchId,
                input.note() == null ? "" : input.note(),
                OffsetDateTime.now(ZoneOffset.UTC),
                id);
            writeEvent(
                actor.tenantId(),
                id,
                "RESCHEDULED",
                existing.get("status", String.class),
                "SCHEDULED",
                "重新排入付款批次 " + input.paymentWindowStart() + " ~ " + input.paymentWindowEnd(),
                actor.userId());
            rows.add(planRow(planRecord(id)));
            rearranged++;
          }
        }
      }
    }

    if (!warnings.isEmpty()) {
      logger.warn("[finance] 批量安排付款有 {} 条未进入：{}", warnings.size(), warnings);
    }
    // 批次的金额与笔数由 DB 触发器从计划派生 ⇒ **必须在计划全部落库之后**再读批次，
    // 否则读到的还是「刚建出来、还没挂计划」的空批次。
    var batches = new ArrayList<FinancePaymentBatchRow>();
    for (var batchId : batchIds) {
      batches.add(batchRow(batchRecord(batchId)));
    }
    return new FinancePaymentPlanScheduleResult(
        payables.size(),
        planned,
        rearranged,
        skipped,
        batches.size(),
        rows,
        List.copyOf(batches),
        List.copyOf(warnings));
  }

  /**
   * 取消一条计划。
   *
   * <p>⚠️ **取消不是删除**：删掉就没有「这笔曾经被排过」的痕迹了。
   * 取消后它那条提醒会**当场失效**（DB 触发器做这件事）。
   */
  @Transactional
  public FinancePaymentPlanRow cancel(UUID id, String reason, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_payment_plan where id = ?", id);
    if (head == null) throw notFound("付款计划 " + id + " 不存在或无权操作");
    String why = reason == null ? "" : reason.trim();
    if (why.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "取消必须写明理由");
    }
    String status = head.get("status", String.class);
    if (PaymentPlanStatus.valueOf(status).terminal()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "付款计划已是终态（" + status + "），不可再取消");
    }
    sql.execute(
        "update finance_payment_plan set status = 'CANCELLED', updated_at = ?::timestamptz,"
            + " version = version + 1 where id = ?",
        OffsetDateTime.now(ZoneOffset.UTC),
        id);
    writeEvent(actor.tenantId(), id, "CANCELLED", status, "CANCELLED", why, actor.userId());
    return planRow(planRecord(id));
  }

  // ================================================================ 付款批次

  /** 付款批次列表：真分页 + 真筛选。 */
  public FinancePaymentBatchPage batches(
      String status, UUID counterpartyRef, String currency, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (status != null && !status.isBlank()) {
      where.add("status = ?");
      binds.add(status.trim());
    }
    if (counterpartyRef != null) {
      where.add("counterparty_ref = ?");
      binds.add(counterpartyRef);
    }
    if (currency != null && !currency.isBlank()) {
      where.add("currency = ?");
      binds.add(currency.trim());
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from finance_payment_batch" + clause, binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select * from finance_payment_batch"
                    + clause
                    + " order by payment_window_start, id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::batchRow);
    return new FinancePaymentBatchPage(items, p, s, total);
  }

  // ================================================================ 资金需求预测

  /**
   * 资金需求预测（**实时聚合**，不是静态表）。
   *
   * <p>⚠️ 流出 = 付款计划（PLANNED / SCHEDULED）的计划付款日与计划金额；
   * 流入 = 12.1.1.1 应收单未核销余额（{@code amount - settled_amount}）的到期日。
   * 两者都按币种分开。
   */
  public FinancePaymentForecast forecast(LocalDate from, LocalDate to, Integer windowDays) {
    LocalDate fromDate = from == null ? TODAY_UTC : from;
    int days = windowDays == null || windowDays <= 0 ? DEFAULT_FORECAST_WINDOW_DAYS : Math.min(windowDays, 92);
    LocalDate toDate =
        to == null ? fromDate.plusDays(DEFAULT_FORECAST_HORIZON_DAYS) : to;
    if (toDate.isBefore(fromDate)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预测区间不合法：止日不能早于起日");
    }
    if (toDate.isAfter(fromDate.plusDays(730))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预测区间最长 730 天");
    }

    // 流出：按 (币种, 计划付款日) 聚合。
    var outflows = new LinkedHashMap<String, LinkedHashMap<LocalDate, BigDecimal>>();
    for (var r :
        sql.fetch(
            "select currency, planned_paid_date as d, sum(planned_amount) as amt, count(*) as cnt"
                + " from finance_payment_plan"
                + " where status in ('PLANNED','SCHEDULED') and planned_paid_date between ?::date and ?::date"
                + " group by currency, planned_paid_date",
            fromDate,
            toDate)) {
      var bucket =
          outflows.computeIfAbsent(r.get("currency", String.class), k -> new LinkedHashMap<>());
      bucket.merge(r.get("d", LocalDate.class), r.get("amt", BigDecimal.class), BigDecimal::add);
    }
    var planCounts = new LinkedHashMap<String, Long>();
    for (var r :
        sql.fetch(
            "select currency, sum(cnt) as total from ("
                + " select currency, planned_paid_date as d, count(*) as cnt from finance_payment_plan"
                + " where status in ('PLANNED','SCHEDULED') and planned_paid_date between ?::date and ?::date"
                + " group by currency, planned_paid_date) t group by currency",
            fromDate,
            toDate)) {
      planCounts.put(r.get("currency", String.class), r.get("total", Long.class));
    }

    // 流入：**只读** 12.1.1.1 的应收单 —— 未核销余额与到期日。
    var inflows = new LinkedHashMap<String, LinkedHashMap<LocalDate, BigDecimal>>();
    for (var r :
        sql.fetch(
            "select currency, due_date as d, sum(amount - settled_amount) as amt from finance_receivable"
                + " where status in ('OPEN','PARTIALLY_SETTLED') and due_date between ?::date and ?::date"
                + " group by currency, due_date",
            fromDate,
            toDate)) {
      var bucket =
          inflows.computeIfAbsent(r.get("currency", String.class), k -> new LinkedHashMap<>());
      bucket.merge(r.get("d", LocalDate.class), r.get("amt", BigDecimal.class), BigDecimal::add);
    }

    var currencies = new java.util.TreeSet<String>();
    currencies.addAll(outflows.keySet());
    currencies.addAll(inflows.keySet());

    var windows = new ArrayList<FinancePaymentForecastWindow>();
    var totals = new ArrayList<FinancePaymentForecastCurrencyTotal>();
    long planCount = 0;

    for (var currency : currencies) {
      var outByDate = outflows.getOrDefault(currency, new LinkedHashMap<>());
      var inByDate = inflows.getOrDefault(currency, new LinkedHashMap<>());
      // 窗口分桶：窗口起点 = fromDate + i × days。
      var outByWindow = bucketByWindow(outByDate, fromDate, days);
      var inByWindow = bucketByWindow(inByDate, fromDate, days);
      var windowIndexes = new java.util.TreeSet<Integer>();
      windowIndexes.addAll(outByWindow.keySet());
      windowIndexes.addAll(inByWindow.keySet());

      BigDecimal currencyOut = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      BigDecimal currencyIn = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      int gapWindows = 0;

      for (var index : windowIndexes) {
        BigDecimal out = outByWindow.getOrDefault(index, BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        BigDecimal in = inByWindow.getOrDefault(index, BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        BigDecimal net = in.subtract(out).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        LocalDate windowStart = fromDate.plusDays((long) index * days);
        LocalDate windowEnd = windowStart.plusDays(days - 1L);
        if (windowEnd.isAfter(toDate)) windowEnd = toDate;
        long count = countPlansInWindow(currency, windowStart, windowEnd);
        planCount += count;
        boolean gap = net.signum() < 0;
        if (gap) gapWindows++;
        currencyOut = currencyOut.add(out);
        currencyIn = currencyIn.add(in);
        windows.add(
            new FinancePaymentForecastWindow(
                currency, windowStart, windowEnd, count, out, in, net, gap));
      }

      BigDecimal netTotal = currencyIn.subtract(currencyOut).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      totals.add(
          new FinancePaymentForecastCurrencyTotal(
              currency,
              currencyOut,
              currencyIn,
              netTotal,
              windowIndexes.size(),
              gapWindows));
    }

    return new FinancePaymentForecast(
        fromDate, days, fromDate, toDate, windows, totals, planCount);
  }

  /** 把「按日聚合」摊到「按窗口聚合」。 */
  private Map<Integer, BigDecimal> bucketByWindow(
      Map<LocalDate, BigDecimal> byDate, LocalDate fromDate, int days) {
    var result = new LinkedHashMap<Integer, BigDecimal>();
    for (var entry : byDate.entrySet()) {
      long offset = java.time.temporal.ChronoUnit.DAYS.between(fromDate, entry.getKey());
      int index = (int) Math.floorDiv(offset, (long) days);
      result.merge(index, entry.getValue(), BigDecimal::add);
    }
    return result;
  }

  /** 某个窗口里这个币种的计划笔数。 */
  private long countPlansInWindow(String currency, LocalDate start, LocalDate end) {
    return sql.fetchOne(
            "select count(*) from finance_payment_plan"
                + " where status in ('PLANNED','SCHEDULED') and currency = ?"
                + " and planned_paid_date between ?::date and ?::date",
            currency,
            start,
            end)
        .get(0, Long.class);
  }

  // ================================================================ 到期提醒

  /** 到期提醒清单：真分页 + 真筛选。 */
  public FinancePaymentReminderPage reminders(PaymentReminderQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (query.status() != null) {
      where.add("r.status = ?");
      binds.add(query.status().name());
    }
    if (query.reminderType() != null) {
      where.add("r.reminder_type = ?");
      binds.add(query.reminderType().name());
    }
    if (query.currency() != null && !query.currency().isBlank()) {
      where.add("r.currency = ?");
      binds.add(query.currency());
    }
    if (query.counterpartyRef() != null) {
      where.add("pa.counterparty_ref = ?");
      binds.add(query.counterpartyRef());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add(
          "(r.plan_id::text ilike ? or pa.payable_no ilike ? or pa.counterparty_name ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from finance_payment_reminder r"
                    + " join finance_payment_plan p on p.tenant_id = r.tenant_id and p.id = r.plan_id"
                    + " join finance_payable pa on pa.tenant_id = r.tenant_id and pa.id = r.payable_id"
                    + clause,
                binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select r.*, p.plan_no, pa.payable_no, pa.counterparty_name"
                    + " from finance_payment_reminder r"
                    + " join finance_payment_plan p on p.tenant_id = r.tenant_id and p.id = r.plan_id"
                    + " join finance_payable pa on pa.tenant_id = r.tenant_id and pa.id = r.payable_id"
                    + clause
                    + " order by r.remind_date, r.id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::reminderRow);

    var summaries = reminderSummaryByCurrency(where, binds);
    long pendingCount = summaries.stream().mapToLong(FinancePaymentReminderCurrencySummary::pendingCount).sum();
    long overdueCount = summaries.stream().mapToLong(FinancePaymentReminderCurrencySummary::overdueCount).sum();
    return new FinancePaymentReminderPage(items, p, s, total, summaries, pendingCount, overdueCount);
  }

  private List<FinancePaymentReminderCurrencySummary> reminderSummaryByCurrency(
      List<String> where, List<Object> binds) {
    var clauses = new ArrayList<String>();
    clauses.addAll(where);
    String clause = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    return sql.fetch(
            "select r.currency,"
                + " sum(r.amount) as amount,"
                + " count(*) as cnt,"
                + " count(*) filter (where r.status = 'PENDING') as pending_count,"
                + " count(*) filter (where r.reminder_type = 'OVERDUE') as overdue_count"
                + " from finance_payment_reminder r"
                + " join finance_payment_plan p on p.tenant_id = r.tenant_id and p.id = r.plan_id"
                + " join finance_payable pa on pa.tenant_id = r.tenant_id and pa.id = r.payable_id"
                + clause
                + " group by r.currency order by r.currency",
            binds.toArray())
        .map(
            r ->
                new FinancePaymentReminderCurrencySummary(
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("cnt", Long.class),
                    r.get("pending_count", Long.class),
                    r.get("overdue_count", Long.class)));
  }

  /**
   * 扫描在册计划，生成 / 更新 / 失效到期提醒。
   *
   * <p>⚠️ **判定是真的**：按计划付款日与基准日比较得出「即将到期 / 今日到期 / 已逾期」。
   *
   * <p>⚠️ **已经人工确认过的提醒不会被重置**：计划变了（日期 / 金额 / 类型）
   * 才会把提醒改回「待处理」；人手动忽略过的提醒**不会**被扫回来。
   *
   * <p>⚠️ **本方法不推送任何东西**：提醒的 {@code push_status} 恒为 {@code NOT_PUSHED}。
   */
  @Transactional
  public FinancePaymentReminderScanResult scanReminders(
      LocalDate asOf, Integer horizonDays, AccessService.Access actor) {
    LocalDate asOfDate = asOf == null ? TODAY_UTC : asOf;
    int horizon =
        horizonDays == null || horizonDays <= 0
            ? DEFAULT_REMINDER_HORIZON_DAYS
            : Math.min(horizonDays, 365);
    LocalDate horizonEnd = asOfDate.plusDays(horizon);
    var warnings = new ArrayList<String>();

    var plans =
        sql.fetch(
            "select p.*, pa.payable_no, pa.due_date from finance_payment_plan p"
                + " join finance_payable pa on pa.tenant_id = p.tenant_id and pa.id = p.payable_id"
                + " where p.status in ('PLANNED','SCHEDULED')"
                + " order by p.planned_paid_date, p.id");
    var existing = new LinkedHashMap<UUID, Record>();
    for (var r : sql.fetch("select * from finance_payment_reminder")) {
      existing.put(r.get("plan_id", UUID.class), r);
    }

    int created = 0;
    int updated = 0;
    int dismissed = 0;
    int unchanged = 0;

    for (var plan : plans) {
      UUID planId = plan.get("id", UUID.class);
      LocalDate paidDate = plan.get("planned_paid_date", LocalDate.class);
      LocalDate dueDate = plan.get("due_date", LocalDate.class);
      BigDecimal amount = plan.get("planned_amount", BigDecimal.class);
      String currency = plan.get("currency", String.class);
      UUID payableId = plan.get("payable_id", UUID.class);
      ReminderType type =
          paidDate.isBefore(asOfDate)
              ? ReminderType.OVERDUE
              : (paidDate.isEqual(asOfDate) ? ReminderType.DUE : ReminderType.UPCOMING);
      var current = existing.get(planId);

      if (paidDate.isAfter(horizonEnd)) {
        // 移出提醒窗口：把还挂着的提醒收起来（**只收系统收的**，人手动忽略的不动）。
        if (current != null && !current.get("status", String.class).equals("DISMISSED")) {
          sql.execute(
              "update finance_payment_reminder set status = 'DISMISSED', dismiss_reason = ?,"
                  + " acknowledged_at = null, acknowledged_by = null, updated_at = ?::timestamptz"
                  + " where id = ?",
              REMINDER_OUT_OF_WINDOW,
              OffsetDateTime.now(ZoneOffset.UTC),
              current.get("id", UUID.class));
          dismissed++;
        }
        continue;
      }

      if (current == null) {
        sql.execute(
            "insert into finance_payment_reminder(id,tenant_id,plan_id,payable_id,reminder_type,"
                + " remind_date,due_date,currency,amount,status,created_at,updated_at)"
                + " values(?,?,?,?,?,?::date,?::date,?,?,'PENDING',?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            actor.tenantId(),
            planId,
            payableId,
            type.name(),
            paidDate,
            dueDate,
            currency,
            amount,
            OffsetDateTime.now(ZoneOffset.UTC),
            OffsetDateTime.now(ZoneOffset.UTC));
        created++;
        continue;
      }

      boolean changed =
          !type.name().equals(current.get("reminder_type", String.class))
              || !paidDate.equals(current.get("remind_date", LocalDate.class))
              || !dueDate.equals(current.get("due_date", LocalDate.class))
              || !amount.equals(current.get("amount", BigDecimal.class));
      boolean systemDismissed =
          current.get("status", String.class).equals("DISMISSED")
              && REMINDER_OUT_OF_WINDOW.equals(current.get("dismiss_reason", String.class));

      if (changed || systemDismissed) {
        sql.execute(
            "update finance_payment_reminder set reminder_type = ?, remind_date = ?::date,"
                + " due_date = ?::date, amount = ?, status = 'PENDING', dismiss_reason = '',"
                + " acknowledged_at = null, acknowledged_by = null, updated_at = ?::timestamptz"
                + " where id = ?",
            type.name(),
            paidDate,
            dueDate,
            amount,
            OffsetDateTime.now(ZoneOffset.UTC),
            current.get("id", UUID.class));
        updated++;
      } else {
        unchanged++;
      }
    }

    if (!warnings.isEmpty()) {
      logger.warn("[finance] 扫描到期提醒有 {} 条提示：{}", warnings.size(), warnings);
    }
    return new FinancePaymentReminderScanResult(
        asOfDate, horizon, plans.size(), created, updated, dismissed, unchanged, List.copyOf(warnings));
  }

  /** 确认一条到期提醒（**真的**记下谁在什么时候确认的）。 */
  @Transactional
  public FinancePaymentReminderRow acknowledgeReminder(
      UUID id, String reason, AccessService.Access actor) {
    var head = reminderRecord(id);
    if (head == null) throw notFound("到期提醒 " + id + " 不存在或无权操作");
    String status = head.get("status", String.class);
    if (status.equals("DISMISSED")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "这条提醒已被忽略，不可再确认");
    }
    if (status.equals("ACKNOWLEDGED")) {
      return reminderRow(head);
    }
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_payment_reminder set status = 'ACKNOWLEDGED', acknowledged_at = ?::timestamptz,"
            + " acknowledged_by = ?, dismiss_reason = '', updated_at = ?::timestamptz where id = ?",
        at,
        actor.userId(),
        at,
        id);
    writeEvent(
        actor.tenantId(),
        head.get("plan_id", UUID.class),
        "REMINDER_ACKNOWLEDGED",
        "PENDING",
        "ACKNOWLEDGED",
        reason == null ? "" : reason.trim(),
        actor.userId());
    return reminderRow(
        reminderRecord(id));
  }

  /** 忽略一条到期提醒。理由必填。 */
  @Transactional
  public FinancePaymentReminderRow dismissReminder(
      UUID id, String reason, AccessService.Access actor) {
    var head = reminderRecord(id);
    if (head == null) throw notFound("到期提醒 " + id + " 不存在或无权操作");
    String why = reason == null ? "" : reason.trim();
    if (why.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "忽略提醒必须写明理由");
    }
    if (head.get("status", String.class).equals("DISMISSED")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "这条提醒已被忽略");
    }
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_payment_reminder set status = 'DISMISSED', dismiss_reason = ?,"
            + " acknowledged_at = null, acknowledged_by = null, updated_at = ?::timestamptz where id = ?",
        why,
        at,
        id);
    writeEvent(
        actor.tenantId(),
        head.get("plan_id", UUID.class),
        "REMINDER_DISMISSED",
        head.get("status", String.class),
        "DISMISSED",
        why,
        actor.userId());
    return reminderRow(
        reminderRecord(id));
  }

  // ================================================================ 账期规则

  /** 账期规则清单（**租户自己维护**，系统不预置任何一行）。 */
  public List<FinancePaymentTermRuleRow> termRules() {
    return sql.fetch("select * from finance_payment_term_rule order by name")
        .map(
            r ->
                new FinancePaymentTermRuleRow(
                    r.get("id", UUID.class),
                    r.get("name", String.class),
                    r.get("counterparty_ref", UUID.class),
                    r.get("currency", String.class),
                    TermBaseKind.valueOf(r.get("base_kind", String.class)),
                    r.get("term_days", Integer.class),
                    r.get("enabled", Boolean.class),
                    r.get("note", String.class),
                    r.get("created_at", OffsetDateTime.class),
                    r.get("updated_at", OffsetDateTime.class)));
  }

  /** 新建一条账期规则。 */
  @Transactional
  public FinancePaymentTermRuleRow createTermRule(
      FinancePaymentTermRuleInput input, AccessService.Access actor) {
    String name = requireText(input.name(), "规则名称");
    TermBaseKind baseKind = input.baseKind() == null ? TermBaseKind.CONFIRMED_DATE : input.baseKind();
    int termDays = input.termDays() == null ? 0 : input.termDays();
    if (termDays < 0 || termDays > 365) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账期天数必须在 0 ~ 365 之间");
    }
    String currency = blankToNull(input.currency());
    if (currency != null) requireCurrency(currency);
    UUID id = UUID.randomUUID();
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_payment_term_rule(id,tenant_id,name,counterparty_ref,currency,base_kind,"
            + " term_days,enabled,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        name,
        input.counterpartyRef(),
        currency,
        baseKind.name(),
        termDays,
        input.enabled() == null || input.enabled(),
        input.note() == null ? "" : input.note().trim(),
        at,
        at);
    return termRuleRow(sql.fetchOne("select * from finance_payment_term_rule where id = ?", id));
  }

  /** 改一条账期规则。 */
  @Transactional
  public FinancePaymentTermRuleRow updateTermRule(
      UUID id, FinancePaymentTermRuleInput input, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_payment_term_rule where id = ?", id);
    if (head == null) throw notFound("账期规则 " + id + " 不存在或无权操作");
    String name = requireText(input.name(), "规则名称");
    TermBaseKind baseKind = input.baseKind() == null ? TermBaseKind.CONFIRMED_DATE : input.baseKind();
    int termDays = input.termDays() == null ? 0 : input.termDays();
    if (termDays < 0 || termDays > 365) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账期天数必须在 0 ~ 365 之间");
    }
    String currency = blankToNull(input.currency());
    if (currency != null) requireCurrency(currency);
    sql.execute(
        "update finance_payment_term_rule set name = ?, counterparty_ref = ?, currency = ?,"
            + " base_kind = ?, term_days = ?, enabled = ?, note = ?, updated_at = ?::timestamptz"
            + " where id = ?",
        name,
        input.counterpartyRef(),
        currency,
        baseKind.name(),
        termDays,
        input.enabled() == null || input.enabled(),
        input.note() == null ? "" : input.note().trim(),
        OffsetDateTime.now(ZoneOffset.UTC),
        id);
    return termRuleRow(sql.fetchOne("select * from finance_payment_term_rule where id = ?", id));
  }

  // ================================================================ 里程碑规则

  /** 里程碑规则清单（含里程碑明细）。 */
  public List<FinancePaymentMilestoneRuleRow> milestoneRules() {
    var rules =
        sql.fetch("select * from finance_payment_milestone_rule order by name")
            .map(this::milestoneRuleHead);
    var result = new ArrayList<FinancePaymentMilestoneRuleRow>();
    for (var rule : rules) {
      var milestones =
          sql.fetch(
                  "select * from finance_payment_milestone where rule_id = ? order by seq, id", rule.id())
              .map(
                  r ->
                      new FinancePaymentMilestoneRow(
                          r.get("id", UUID.class),
                          r.get("seq", Integer.class),
                          r.get("name", String.class),
                          r.get("offset_days", Integer.class),
                          r.get("trigger_date", LocalDate.class),
                          r.get("ratio", BigDecimal.class)));
      result.add(
          new FinancePaymentMilestoneRuleRow(
              rule.id(),
              rule.name(),
              rule.counterpartyRef(),
              rule.currency(),
              rule.enabled(),
              rule.note(),
              milestones,
              rule.createdAt(),
              rule.updatedAt()));
    }
    return List.copyOf(result);
  }

  /** 新建一套里程碑规则（**整套一起落**：比例之和必须为 1）。 */
  @Transactional
  public FinancePaymentMilestoneRuleRow createMilestoneRule(
      FinancePaymentMilestoneRuleInput input, AccessService.Access actor) {
    String name = requireText(input.name(), "规则名称");
    String currency = blankToNull(input.currency());
    if (currency != null) requireCurrency(currency);
    var milestones = normalizeMilestones(input.milestones());
    UUID id = UUID.randomUUID();
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_payment_milestone_rule(id,tenant_id,name,counterparty_ref,currency,"
            + " enabled,note,created_at,updated_at) values(?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        name,
        input.counterpartyRef(),
        currency,
        input.enabled() == null || input.enabled(),
        input.note() == null ? "" : input.note().trim(),
        at,
        at);
    writeMilestones(id, milestones, actor);
    return milestoneRuleRow(id);
  }

  /**
   * 改一套里程碑规则（**里程碑整套替换**）。
   *
   * <p>⚠️ 比例之和必须为 1：整体替换时会有「只插了第一期」的中间态，
   * 所以这条**跨行约束由服务层对着整份配置校验**（DB 层只兜单行 0 < ratio ≤ 1）。
   */
  @Transactional
  public FinancePaymentMilestoneRuleRow updateMilestoneRule(
      UUID id, FinancePaymentMilestoneRuleInput input, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_payment_milestone_rule where id = ?", id);
    if (head == null) throw notFound("里程碑规则 " + id + " 不存在或无权操作");
    String name = requireText(input.name(), "规则名称");
    String currency = blankToNull(input.currency());
    if (currency != null) requireCurrency(currency);
    var milestones = normalizeMilestones(input.milestones());
    sql.execute(
        "update finance_payment_milestone_rule set name = ?, counterparty_ref = ?, currency = ?,"
            + " enabled = ?, note = ?, updated_at = ?::timestamptz where id = ?",
        name,
        input.counterpartyRef(),
        currency,
        input.enabled() == null || input.enabled(),
        input.note() == null ? "" : input.note().trim(),
        OffsetDateTime.now(ZoneOffset.UTC),
        id);
    sql.execute("delete from finance_payment_milestone where rule_id = ?", id);
    writeMilestones(id, milestones, actor);
    return milestoneRuleRow(id);
  }

  // ================================================================ 内部：取数

  /** 候选应付：未付清、未作废、可选按供应商与到期窗口过滤。 */
  private List<PayableFact> candidatePayables(
      UUID counterpartyRef, LocalDate dueFrom, LocalDate dueTo) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("status in ('OPEN','PARTIALLY_PAID')");
    where.add("unpaid_amount > 0");
    if (counterpartyRef != null) {
      where.add("counterparty_ref = ?");
      binds.add(counterpartyRef);
    }
    if (dueFrom != null) {
      where.add("due_date >= ?::date");
      binds.add(dueFrom);
    }
    if (dueTo != null) {
      where.add("due_date <= ?::date");
      binds.add(dueTo);
    }
    return sql.fetch(
            "select id, payable_no, due_date, (confirmed_at at time zone 'UTC')::date as confirmed_date,"
                + " currency, unpaid_amount, counterparty_type, counterparty_ref, counterparty_name"
                + " from finance_payable where "
                + String.join(" and ", where)
                + " order by due_date, id",
            binds.toArray())
        .map(
            r ->
                new PayableFact(
                    r.get("id", UUID.class),
                    r.get("payable_no", String.class),
                    r.get("due_date", LocalDate.class),
                    r.get("confirmed_date", LocalDate.class),
                    r.get("currency", String.class),
                    r.get("unpaid_amount", BigDecimal.class),
                    r.get("counterparty_type", String.class),
                    r.get("counterparty_ref", UUID.class),
                    r.get("counterparty_name", String.class)));
  }

  /** 按付款条件算出一张应付对应的计划草稿（到期日 1 条 / 账期 1 条 / 里程碑 N 条）。 */
  private List<DraftPlan> draftsFor(
      PayableFact payable,
      PaymentCondition condition,
      UUID termRuleId,
      UUID milestoneRuleId,
      List<String> warnings) {
    var drafts = new ArrayList<DraftPlan>();
    BigDecimal unpaid = money(payable.unpaid());
    switch (condition) {
      case DUE_DATE -> {
        drafts.add(
            new DraftPlan(
                payable.id(),
                condition,
                null,
                null,
                null,
                payable.dueDate(),
                payable.currency(),
                unpaid,
                unpaid));
      }
      case TERM -> {
        var rule = resolveTermRule(termRuleId, payable, warnings);
        if (rule == null) break;
        LocalDate base =
            rule.baseKind() == TermBaseKind.DUE_DATE
                ? payable.dueDate()
                : payable.confirmedDate();
        drafts.add(
            new DraftPlan(
                payable.id(),
                condition,
                rule.id(),
                null,
                null,
                base.plusDays(rule.termDays()),
                payable.currency(),
                unpaid,
                unpaid));
      }
      case MILESTONE -> {
        var rule = resolveMilestoneRule(milestoneRuleId, payable, warnings);
        if (rule == null) break;
        for (var milestone : rule.milestones()) {
          LocalDate trigger =
              milestone.triggerDate() != null
                  ? milestone.triggerDate()
                  : payable.confirmedDate().plusDays(milestone.offsetDays());
          BigDecimal amount = money(unpaid.multiply(milestone.ratio()));
          if (amount.signum() <= 0) {
            warnings.add(
                "应付 "
                    + payable.no()
                    + " 的里程碑「"
                    + milestone.name()
                    + "」按比例算出来是 0 元，已跳过（不造 0 元计划）");
            continue;
          }
          drafts.add(
              new DraftPlan(
                  payable.id(),
                  condition,
                  null,
                  milestone.id(),
                  milestone.name(),
                  trigger,
                  payable.currency(),
                  amount,
                  unpaid));
        }
      }
    }
    return drafts;
  }

  /** 解析账期规则：显式指定优先，否则按（供应商 × 币种）最具体优先自动选。 */
  private TermRule resolveTermRule(UUID termRuleId, PayableFact payable, List<String> warnings) {
    if (termRuleId != null) {
      var row = sql.fetchOne("select * from finance_payment_term_rule where id = ?", termRuleId);
      if (row == null) {
        warnings.add("账期规则 " + termRuleId + " 不存在或无权使用，已跳过");
        return null;
      }
      if (!row.get("enabled", Boolean.class)) {
        warnings.add("账期规则「" + row.get("name", String.class) + "」当前是禁用的，已跳过");
        return null;
      }
      String ruleCurrency = row.get("currency", String.class);
      if (ruleCurrency != null && !ruleCurrency.equals(payable.currency())) {
        warnings.add(
            "账期规则「"
                + row.get("name", String.class)
                + "」只适用于 "
                + ruleCurrency
                + "，与应付的币种 "
                + payable.currency()
                + " 不符，已跳过");
        return null;
      }
      return new TermRule(
          row.get("id", UUID.class),
          row.get("name", String.class),
          TermBaseKind.valueOf(row.get("base_kind", String.class)),
          row.get("term_days", Integer.class),
          ruleCurrency);
    }
    var row =
        sql.fetchOne(
            "select * from finance_payment_term_rule where enabled"
                + " and (counterparty_ref is null or counterparty_ref = ?)"
                + " and (currency is null or currency = ?)"
                + " order by (counterparty_ref is not null) desc, (currency is not null) desc,"
                + " created_at, id limit 1",
            payable.counterpartyRef(),
            payable.currency());
    if (row == null) {
      warnings.add(
          "没有可用的账期规则（供应商 "
              + payable.counterpartyName()
              + " / 币种 "
              + payable.currency()
              + "）：账期条件排不出计划 —— 请先配置并启用一条账期规则");
      return null;
    }
    return new TermRule(
        row.get("id", UUID.class),
        row.get("name", String.class),
        TermBaseKind.valueOf(row.get("base_kind", String.class)),
        row.get("term_days", Integer.class),
        row.get("currency", String.class));
  }

  /** 解析里程碑规则：显式指定优先，否则按（供应商 × 币种）最具体优先自动选。 */
  private MilestoneRule resolveMilestoneRule(
      UUID milestoneRuleId, PayableFact payable, List<String> warnings) {
    if (milestoneRuleId != null) {
      var row = sql.fetchOne("select * from finance_payment_milestone_rule where id = ?", milestoneRuleId);
      if (row == null) {
        warnings.add("里程碑规则 " + milestoneRuleId + " 不存在或无权使用，已跳过");
        return null;
      }
      if (!row.get("enabled", Boolean.class)) {
        warnings.add("里程碑规则「" + row.get("name", String.class) + "」当前是禁用的，已跳过");
        return null;
      }
      String ruleCurrency = row.get("currency", String.class);
      if (ruleCurrency != null && !ruleCurrency.equals(payable.currency())) {
        warnings.add(
            "里程碑规则「"
                + row.get("name", String.class)
                + "」只适用于 "
                + ruleCurrency
                + "，与应付的币种 "
                + payable.currency()
                + " 不符，已跳过");
        return null;
      }
      return new MilestoneRule(
          row.get("id", UUID.class),
          row.get("name", String.class),
          ruleCurrency,
          milestonesOf(row.get("id", UUID.class)));
    }
    var row =
        sql.fetchOne(
            "select * from finance_payment_milestone_rule where enabled"
                + " and (counterparty_ref is null or counterparty_ref = ?)"
                + " and (currency is null or currency = ?)"
                + " order by (counterparty_ref is not null) desc, (currency is not null) desc,"
                + " created_at, id limit 1",
            payable.counterpartyRef(),
            payable.currency());
    if (row == null) {
      warnings.add(
          "没有可用的里程碑规则（供应商 "
              + payable.counterpartyName()
              + " / 币种 "
              + payable.currency()
              + "）：里程碑条件排不出计划 —— 请先配置并启用一套里程碑规则");
      return null;
    }
    return new MilestoneRule(
        row.get("id", UUID.class),
        row.get("name", String.class),
        row.get("currency", String.class),
        milestonesOf(row.get("id", UUID.class)));
  }

  private List<MilestoneFact> milestonesOf(UUID ruleId) {
    return sql.fetch(
            "select * from finance_payment_milestone where rule_id = ? order by seq, id", ruleId)
        .map(
            r ->
                new MilestoneFact(
                    r.get("id", UUID.class),
                    r.get("seq", Integer.class),
                    r.get("name", String.class),
                    r.get("offset_days", Integer.class),
                    r.get("trigger_date", LocalDate.class),
                    r.get("ratio", BigDecimal.class)));
  }

  /** 查这张应付下仍然在册的计划（非里程碑条件命中任一条即可）。 */
  private Record activePlanOf(UUID payableId, UUID milestoneId) {
    if (milestoneId != null) {
      return sql.fetchOne(
          "select * from finance_payment_plan where payable_id = ? and milestone_id = ?"
              + " and status <> 'CANCELLED'",
          payableId,
          milestoneId);
    }
    return sql.fetchOne(
        "select * from finance_payment_plan where payable_id = ? and payment_condition <> 'MILESTONE'"
            + " and status <> 'CANCELLED'",
        payableId);
  }

  // ================================================================ 内部：落库

  private UUID insertPlan(
      DraftPlan draft,
      LocalDate plannedPaidDate,
      UUID batchId,
      PaymentPlanStatus status,
      String note,
      AccessService.Access actor) {
    UUID id = UUID.randomUUID();
    String no = nextReference("PLN");
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_payment_plan(id,tenant_id,plan_no,payable_id,batch_id,payment_condition,"
            + " term_rule_id,milestone_id,condition_date,planned_paid_date,currency,planned_amount,"
            + " unpaid_snapshot,status,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?::date,?::date,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        no,
        draft.payableId(),
        batchId,
        draft.condition().name(),
        draft.termRuleId(),
        draft.milestoneId(),
        draft.conditionDate(),
        plannedPaidDate,
        draft.currency(),
        draft.plannedAmount(),
        draft.unpaidSnapshot(),
        status.name(),
        note == null ? "" : note.trim(),
        at,
        at);
    return id;
  }

  private UUID insertBatch(
      PayableFact payable,
      LocalDate dueFrom,
      LocalDate dueTo,
      LocalDate windowStart,
      LocalDate windowEnd,
      String currency,
      String note,
      AccessService.Access actor) {
    UUID id = UUID.randomUUID();
    String no = nextReference("PLB");
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_payment_batch(id,tenant_id,batch_no,counterparty_type,counterparty_ref,"
            + " counterparty_name,due_window_start,due_window_end,payment_window_start,payment_window_end,"
            + " currency,status,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?::date,?::date,?::date,?::date,?,'SCHEDULED',?,?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        no,
        payable.counterpartyType(),
        payable.counterpartyRef(),
        payable.counterpartyName(),
        dueFrom,
        dueTo,
        windowStart,
        windowEnd,
        currency,
        note == null ? "" : note.trim(),
        at,
        at);
    return id;
  }

  private void writeMilestones(
      UUID ruleId, List<MilestoneFact> milestones, AccessService.Access actor) {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    for (var milestone : milestones) {
      sql.execute(
          "insert into finance_payment_milestone(id,tenant_id,rule_id,seq,name,offset_days,"
              + " trigger_date,ratio,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?::date,?,?::timestamptz,?::timestamptz)",
          milestone.id(),
          actor.tenantId(),
          ruleId,
          milestone.seq(),
          milestone.name(),
          milestone.offsetDays(),
          milestone.triggerDate(),
          milestone.ratio(),
          at,
          at);
    }
  }

  /**
   * 校验并整理一套里程碑。
   *
   * <p>⚠️ **比例之和必须为 1**：一套里程碑是**整体替换**写入的，
   * 「加起来不是 100%」是跨行约束，只能对着整份配置判 ⇒ 放服务层。
   */
  private List<MilestoneFact> normalizeMilestones(List<FinancePaymentMilestoneInput> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "里程碑规则至少要有一个里程碑");
    }
    var result = new ArrayList<MilestoneFact>();
    BigDecimal sum = BigDecimal.ZERO.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    int seq = 0;
    for (var input : inputs) {
      seq++;
      String name = requireText(input.name(), "里程碑名称");
      if ((input.offsetDays() == null) == (input.triggerDate() == null)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "里程碑「" + name + "」的触发日必须二选一：填「相对确认日的天数」或填「绝对日期」，不能都填也不能都不填");
      }
      if (input.offsetDays() != null && input.offsetDays() < -365) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "里程碑「" + name + "」的偏移天数不能早于 365 天之前");
      }
      BigDecimal ratio = ratio(input.ratio());
      if (ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "里程碑「" + name + "」的比例必须大于 0 且不超过 1");
      }
      sum = sum.add(ratio);
      result.add(
          new MilestoneFact(
              UUID.randomUUID(),
              input.seq() == null ? seq : input.seq(),
              name,
              input.offsetDays(),
              input.triggerDate(),
              ratio));
    }
    if (sum.compareTo(BigDecimal.ONE) != 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "一套里程碑的比例之和必须为 1（当前 " + sum.toPlainString() + "）："
              + "里程碑合起来要正好覆盖这笔应付的全部未付金额");
    }
    return result;
  }

  private void writeEvent(
      UUID tenantId,
      UUID planId,
      String type,
      String from,
      String to,
      String reason,
      UUID actorId) {
    sql.execute(
        "insert into finance_payment_plan_event(id,tenant_id,plan_id,event_type,from_status,"
            + " to_status,reason,actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        planId,
        type,
        from == null ? "" : from,
        to == null ? "" : to,
        reason == null ? "" : reason,
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  // ================================================================ 内部：映射

  private Record planRecord(UUID id) {
    return sql.fetchOne(
        "select p.*, pa.payable_no, pa.due_date, pa.counterparty_type, pa.counterparty_ref,"
            + " pa.counterparty_name, pa.unpaid_amount as current_unpaid, b.batch_no,"
            + " ms.name as milestone_name"
            + " from finance_payment_plan p"
            + " join finance_payable pa on pa.tenant_id = p.tenant_id and pa.id = p.payable_id"
            + " left join finance_payment_batch b on b.tenant_id = p.tenant_id and b.id = p.batch_id"
            + " left join finance_payment_milestone ms on ms.tenant_id = p.tenant_id and ms.id = p.milestone_id"
            + " where p.id = ?",
        id);
  }

  /**
   * 读一条提醒（**带计划号 / 应付单号 / 供应商名**）。
   *
   * <p>⚠️ 必须走这个带 JOIN 的取法：提醒表自己只有 plan_id / payable_id，
   * 单号与供应商名在计划与应付上 ⇒ 直接 {@code select *} 出来的行**没有**这些列。
   */
  private Record reminderRecord(UUID id) {
    return sql.fetchOne(
        "select r.*, p.plan_no, pa.payable_no, pa.counterparty_name"
            + " from finance_payment_reminder r"
            + " join finance_payment_plan p on p.tenant_id = r.tenant_id and p.id = r.plan_id"
            + " join finance_payable pa on pa.tenant_id = r.tenant_id and pa.id = r.payable_id"
            + " where r.id = ?",
        id);
  }

  private Record batchRecord(UUID id) {
    return sql.fetchOne("select * from finance_payment_batch where id = ?", id);
  }

  private FinancePaymentPlanRow planRow(Record r) {
    return new FinancePaymentPlanRow(
        r.get("id", UUID.class),
        r.get("plan_no", String.class),
        r.get("payable_id", UUID.class),
        r.get("payable_no", String.class),
        r.get("batch_id", UUID.class),
        r.get("batch_no", String.class),
        PaymentCondition.valueOf(r.get("payment_condition", String.class)),
        r.get("term_rule_id", UUID.class),
        r.get("milestone_id", UUID.class),
        r.get("milestone_name", String.class),
        r.get("condition_date", LocalDate.class),
        r.get("planned_paid_date", LocalDate.class),
        r.get("clamped", Boolean.class),
        r.get("currency", String.class),
        r.get("planned_amount", BigDecimal.class),
        r.get("unpaid_snapshot", BigDecimal.class),
        r.get("current_unpaid", BigDecimal.class),
        PaymentPlanStatus.valueOf(r.get("status", String.class)),
        r.get("execution_owner_module", String.class),
        r.get("execution_available", Boolean.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinancePaymentBatchRow batchRow(Record r) {
    return new FinancePaymentBatchRow(
        r.get("id", UUID.class),
        r.get("batch_no", String.class),
        PaymentCounterpartyType.valueOf(r.get("counterparty_type", String.class)),
        r.get("counterparty_ref", UUID.class),
        r.get("counterparty_name", String.class),
        r.get("due_window_start", LocalDate.class),
        r.get("due_window_end", LocalDate.class),
        r.get("payment_window_start", LocalDate.class),
        r.get("payment_window_end", LocalDate.class),
        r.get("currency", String.class),
        r.get("planned_amount", BigDecimal.class),
        r.get("plan_count", Integer.class),
        PaymentBatchStatus.valueOf(r.get("status", String.class)),
        r.get("execution_owner_module", String.class),
        r.get("execution_available", Boolean.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinancePaymentReminderRow reminderRow(Record r) {
    return new FinancePaymentReminderRow(
        r.get("id", UUID.class),
        r.get("plan_id", UUID.class),
        r.get("plan_no", String.class),
        r.get("payable_id", UUID.class),
        r.get("payable_no", String.class),
        r.get("counterparty_name", String.class),
        ReminderType.valueOf(r.get("reminder_type", String.class)),
        r.get("remind_date", LocalDate.class),
        r.get("due_date", LocalDate.class),
        r.get("currency", String.class),
        r.get("amount", BigDecimal.class),
        ReminderStatus.valueOf(r.get("status", String.class)),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("acknowledged_by", UUID.class),
        r.get("dismiss_reason", String.class),
        r.get("push_owner_module", String.class),
        r.get("push_status", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinancePaymentTermRuleRow termRuleRow(Record r) {
    return new FinancePaymentTermRuleRow(
        r.get("id", UUID.class),
        r.get("name", String.class),
        r.get("counterparty_ref", UUID.class),
        r.get("currency", String.class),
        TermBaseKind.valueOf(r.get("base_kind", String.class)),
        r.get("term_days", Integer.class),
        r.get("enabled", Boolean.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinancePaymentMilestoneRuleRow milestoneRuleHead(Record r) {
    return new FinancePaymentMilestoneRuleRow(
        r.get("id", UUID.class),
        r.get("name", String.class),
        r.get("counterparty_ref", UUID.class),
        r.get("currency", String.class),
        r.get("enabled", Boolean.class),
        r.get("note", String.class),
        List.of(),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinancePaymentMilestoneRuleRow milestoneRuleRow(UUID id) {
    for (var rule : milestoneRules()) {
      if (rule.id().equals(id)) return rule;
    }
    throw notFound("里程碑规则 " + id + " 不存在或无权查看");
  }

  // ================================================================ 工具

  private static String conditionLabel(String raw) {
    try {
      return PaymentCondition.valueOf(raw).label();
    } catch (IllegalArgumentException e) {
      return raw;
    }
  }

  private static LocalDate clamp(LocalDate date, LocalDate start, LocalDate end) {
    if (date.isBefore(start)) return start;
    if (date.isAfter(end)) return end;
    return date;
  }

  private static String nextReference(String prefix) {
    return prefix
        + "-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal ratio(BigDecimal value) {
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "里程碑比例必须填写");
    }
    return value.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
    }
    return value.trim();
  }

  private static String requireCurrency(String currency) {
    if (currency == null || !currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三个大写字母（如 USD）");
    }
    return currency;
  }

  private static ResponseStatusException notFound(String detail) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, detail);
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    var out = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) out[i] = binds.get(i);
    System.arraycopy(tail, 0, out, binds.size(), tail.length);
    return out;
  }
}
