package com.starcon.finance;

import static com.starcon.finance.AccountingPeriodModels.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.5.1.2 会计期间。
 *
 * <p>蓝图 12-finance.md：「管理会计期间（自然月或 4-4-5 fiscal 周期）：期间状态（开放/关闭）、
 * 关账与重开、期间内凭证限制。」输入：「会计日历配置；关账/重开操作；审批。」输出：「期间状态；关账记录。」
 * 关系：「上游 = 会计日历；下游 = 12.5.2.1 凭证（已关闭期间禁止过账）。**重开已关期间是高风险操作**，
 * 必须审批留痕。」
 *
 * <p><b>本模块的五条设计主线</b>：
 *
 * <ol>
 *   <li><b>接管 V357 的 {@code finance_voucher_period}</b>（不是新建一张平行表）。
 *       V357 第六节交接面原文：「期间表的**最终归属是 12.5.1.2**」⇒ 本模块补 fiscal 周期与重开留痕，
 *       12.5.2.1 的凭证继续读同一张表的 {@code status} 做过账闸门，<b>它的结构一个字都不用改</b>。
 *   <li><b>重开必须审批留痕 —— 判两次。</b> 服务层：向 17 审批（identity）申请与读取审批状态；
 *       数据层：{@code guard_finance_voucher_period_state} 要求本地镜像里**已经有一条 APPROVE 动作**
 *       ⇒ 绕过服务层直连 SQL 也重开不了一个没审批的期间。
 *   <li><b>审批不可达就如实说不可达。</b> identity 读不到时整个调用失败（503），
 *       期间**保持关闭** —— 绝不「读不到就当批过了」。
 *   <li><b>fiscal 周期由日历生成，不由人肉填。</b> 自然月 = 12 个日历月；
 *       4-4-5 = 4-4-5 循环 × 4 轮 = 52 周；周期长度是<b>生成列</b>，不可能与日期漂移。
 *   <li><b>期间身份字段一经入账不可改。</b> 凭证按 {@code period_id} 引用期间 ⇒ 改了区间等于改账期。
 * </ol>
 */
@Service
public class AccountingPeriodService {

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 本模块在 identity 侧登记的重开单据类型码。
   *
   * <p>⇒ 租户要给自己的重开配审批流，就在 17.4.1.1 里注册一个
   * {@code document_type_code = 'ACCOUNTING_PERIOD_REOPEN'} 的流程并置为 ACTIVE。
   *
   * <p>⚠️ **没有注册审批流 ⇒ 拒绝重开**（不是「默认放行」）：重开已关期间是蓝图明写的高风险操作。
   */
  public static final String REOPEN_DOCUMENT_TYPE_CODE = "ACCOUNTING_PERIOD_REOPEN";

  /** 审批提交时的来源系统（恒为 finance：本模块是提交方）。 */
  private static final String APPROVAL_SOURCE_SYSTEM = "finance";

  private final DSLContext sql;
  private final ApprovalPort approvals;

  public AccountingPeriodService(DSLContext sql, ApprovalPort approvals) {
    this.sql = sql;
    this.approvals = approvals;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 「关账检查清单」必须单独报成缺口 —— 它属 12.5.1.3，**未交付**。
   * 本模块的关账**不假装**跑过检查清单。
   */
  public FinanceAccountingPeriodCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceAccountingPeriodCapability>();
    var gaps = new ArrayList<FinanceAccountingPeriodDownstreamGap>();

    capabilities.add(
        new FinanceAccountingPeriodCapability(
            "12.5.1.2",
            "会计日历配置（自然月 / 4-4-5 fiscal 周期）",
            true,
            "finance_accounting_calendar（日历类型 + 财年起始月 + 4-4-5 锚点日 + 本位币）",
            "",
            "蓝图输入里的「会计日历配置」：一套日历 = 一种账期体系。"
                + "自然月日历按财年起始月推 12 个日历月；4-4-5 日历按锚点日推 12 个 13 周周期"
                + "（4-4-5 循环 × 4 轮 = 52 周）。日历一旦被期间引用，"
                + "类型 / 起始月 / 锚点日 / 本位币**不可再改**（DB 触发器钉死）—— 改了等于改已生成的账期。"));

    capabilities.add(
        new FinanceAccountingPeriodCapability(
            "12.5.1.2",
            "按日历生成期间（fiscal 周期落库）",
            true,
            "finance_voucher_period.calendar_id / fiscal_year / fiscal_period_no / period_type / period_length_days",
            "",
            "蓝图的「自然月或 4-4-5 fiscal 周期」落成结构而不是文案："
                + "每个期间都带自己的**类型**（自然月 / 4-4-5 / 自定义），"
                + "报表按财年取数时不必回头猜「这期到底是自然月还是 4-4-5」。"
                + "🔴 **周期长度是生成列**（period_end - period_start + 1）⇒ 与日期不可能漂移；"
                + "4-4-5 的周期长度只能是 28 或 35 天（表级 CHECK 钉死）。"
                + "重跑生成时**已存在的期间编码被跳过而不是覆盖**（用户手改过的期间不能被冲掉），"
                + "被跳过的编码逐个报出来。"));

    capabilities.add(
        new FinanceAccountingPeriodCapability(
            "12.5.1.2",
            "期间状态与关账",
            true,
            "finance_voucher_period.status（OPEN / CLOSED）+ 关闭留痕（关账人 / 时刻 / 原因）",
            "",
            "蓝图「期间状态（开放/关闭）、关账与重开」：关账必须写明关闭人 / 关闭时刻 / 关闭原因，"
                + "缺一不可（DB 守卫钉死，服务层绕不过）。"
                + "🔴 **已关闭期间整行冻结**：关账留痕本身不可再改，要改只能整体重开。"));

    capabilities.add(
        new FinanceAccountingPeriodCapability(
            "12.5.1.2",
            "关账记录（append-only）",
            true,
            "finance_accounting_period_event（建期 / 关账 / 提交重开申请 / 重开 / 变更）",
            "",
            "蓝图输出写死「**关账记录**」⇒ 关账那一刻必须留痕。"
                + "留痕表**只能追加**（运行角色 UPDATE/DELETE 已收回 + 触发器兜底）："
                + "它是**举证凭据**，事后要能回答「这期关过没有、谁关的、为什么又开了」。"));

    capabilities.add(
        new FinanceAccountingPeriodCapability(
            "12.5.1.2",
            "重开已关期间（高风险，必须审批留痕）",
            true,
            "17 审批（identity）授权 HTTP API + finance_accounting_period_approval（本地镜像）"
                + " + guard_finance_voucher_period_state",
            "",
            "蓝图加粗句「**重开已关期间是高风险操作**，必须审批留痕」的落地，**判两次**："
                + "① 服务层向 17 审批申请重开（单据类型 ACCOUNTING_PERIOD_REOPEN），"
                + "只有审批**已批准**才真的重开，并把整条审批链路镜像到本地；"
                + "② 数据层要求本地镜像里**已经有一条 APPROVE 动作** ⇒ "
                + "**绕过服务层直连 SQL 也重开不了一个没审批的期间**。"
                + "⚠️ **没有注册生效审批流 ⇒ 拒绝重开**（不是默认放行）；"
                + "**审批服务不可达 ⇒ 整个调用失败、期间保持关闭** —— 绝不「读不到就当批过了」。"));

    capabilities.add(
        new FinanceAccountingPeriodCapability(
            "12.5.2.1",
            "期间内凭证限制（已关闭期间禁止过账）",
            true,
            "12.5.2.1 的 guard_finance_voucher_post_period（读本表 status）",
            "",
            "蓝图关系行写死「下游 = 12.5.2.1 凭证（**已关闭期间禁止过账**）」："
                + "12.5.2.1 的过账触发器在**过账那一刻**查本表的 status，"
                + "期间不是 OPEN ⇒ 过账写不进去（直连 SQL 也不行），本位币不一致同样写不进去。"
                + "🔴 本模块**没有**改那道守卫一个字 —— 它读的就是本表的 status。"));

    capabilities.add(
        new FinanceAccountingPeriodCapability(
            "12.5.1.3",
            "关账检查清单（阻断项 / 可结转未清项）",
            false,
            "本模块不跑任何检查清单",
            "12.5.1.3",
            "蓝图明写「关账检查清单是防错最后一道关」，那是 **12.5.1.3 关账**的职责，"
                + "**未交付**。本模块只提供「关账这个动作」与「关账记录」，"
                + "**不假装**跑过清单 —— 界面上的「关账」不代表清单已通过。"));

    gaps.add(
        new FinanceAccountingPeriodDownstreamGap(
            "12.5.1.3",
            "关账",
            "finance",
            false,
            "12.5 账簿与科目",
            "关账流程与检查清单未交付：本模块的关账**不校验**未对账差异 / 未结转成本 / 未核销款项 /"
                + "未过账凭证。清单归 12.5.1.3。"));

    gaps.add(
        new FinanceAccountingPeriodDownstreamGap(
            "12.5.2.2",
            "财务报表",
            "finance",
            false,
            "12.5 凭证与报表",
            "报表未交付：期末结转 / 结转损益 / 资产负债表取数都要按**财年**汇总，"
                + "本模块已把 fiscal_year / fiscal_period_no / 期间类型备好，"
                + "但**不产出任何报表数字**。"));

    gaps.add(
        new FinanceAccountingPeriodDownstreamGap(
            "12.5.2.1",
            "凭证管理",
            "finance",
            true,
            "12.5 凭证与报表",
            "凭证**已交付**（同服务同库）：它继续读本表 status / period_code / base_currency 做过账闸门，"
                + "本模块新增的 fiscal 列与 reopen 列对它**完全透明**。"));

    return new FinanceAccountingPeriodCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinanceAccountingPeriodOptions options() {
    var statuses =
        sql.fetch("select distinct status from finance_voucher_period order by status")
            .map(r -> r.get(0, String.class));
    var types =
        sql.fetch("select distinct period_type from finance_voucher_period order by period_type")
            .map(r -> r.get(0, String.class));
    var currencies =
        sql.fetch("select distinct base_currency from finance_voucher_period order by base_currency")
            .map(r -> r.get(0, String.class));
    var calendars =
        sql.fetch(
                "select distinct calendar_id from finance_voucher_period"
                    + " where calendar_id is not null order by calendar_id")
            .map(r -> r.get(0, UUID.class));
    var years =
        sql.fetch(
                "select distinct fiscal_year from finance_voucher_period"
                    + " where fiscal_year is not null order by fiscal_year desc")
            .map(r -> r.get(0, Integer.class));
    return new FinanceAccountingPeriodOptions(statuses, types, currencies, calendars, years);
  }

  // ================================================================ 期间列表 / 详情

  /**
   * 期间列表：真分页 + 真筛选 + 汇总。
   *
   * <p>⚠️ **汇总覆盖整个筛选结果集**，不是当前页 —— 分页一变汇总就变的话，
   * 「翻到第二页看到总数变小」会被读成「数据丢了」。
   */
  public FinanceAccountingPeriodPage page(AccountingPeriodQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from finance_voucher_period p" + clause, binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select p.*, c.calendar_code, c.calendar_name"
                    + " from finance_voucher_period p"
                    + " left join finance_accounting_calendar c"
                    + "   on c.tenant_id = p.tenant_id and c.id = p.calendar_id"
                    + clause
                    + " order by p.period_start desc, p.period_code limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::periodRow);
    var summary =
        sql.fetch(
                "select p.status, count(*) as cnt from finance_voucher_period p"
                    + clause
                    + " group by p.status order by p.status",
                binds.toArray())
            .map(
                r ->
                    new FinanceAccountingPeriodSummary(
                        AccountingPeriodStatus.valueOf(r.get("status", String.class)),
                        AccountingPeriodStatus.valueOf(r.get("status", String.class)).label(),
                        r.get("cnt", Long.class)));
    return new FinanceAccountingPeriodPage(items, p, s, total, summary);
  }

  /** 期间详情：期间 + 事件留痕（关账记录）+ 审批链路。 */
  public FinanceAccountingPeriodDetail detail(UUID id) {
    var period = requirePeriod(id);
    var events = events(id);
    var approvals = approvals(id);
    return new FinanceAccountingPeriodDetail(periodRow(period), events, approvals);
  }

  /** 期间事件留痕（关账记录），按时间倒序。 */
  public List<FinanceAccountingPeriodEventRow> events(UUID id) {
    requirePeriod(id);
    return sql.fetch(
            "select * from finance_accounting_period_event where period_id = ?"
                + " order by occurred_at desc, id desc",
            id)
        .map(this::eventRow);
  }

  /** 期间的审批链路（identity 链路的本地镜像）。 */
  public List<FinanceAccountingPeriodApprovalRow> approvals(UUID id) {
    requirePeriod(id);
    return sql.fetch(
            "select * from finance_accounting_period_approval where period_id = ?"
                + " order by decided_at, id",
            id)
        .map(this::approvalRow);
  }

  private void applyFilters(AccountingPeriodQuery q, List<String> where, List<Object> binds) {
    if (q.status() != null) {
      where.add("p.status = ?");
      binds.add(q.status().name());
    }
    if (q.periodType() != null) {
      where.add("p.period_type = ?");
      binds.add(q.periodType().name());
    }
    if (q.calendarId() != null) {
      where.add("p.calendar_id = ?");
      binds.add(q.calendarId());
    }
    if (q.fiscalYear() != null) {
      where.add("p.fiscal_year = ?");
      binds.add(q.fiscalYear());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add("(p.period_code ilike ? or p.closed_reason ilike ? or p.reopen_reason ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  // ================================================================ 期间写入

  /**
   * 手工登记一个**自定义**期间。
   *
   * <p>⚠️ 自定义期间**不绑定日历**（{@code fiscal_year / fiscal_period_no} 为空）：
   * 要有 fiscal 周期请用「从会计日历生成期间」。
   */
  @Transactional
  public FinanceAccountingPeriodRow create(
      FinanceAccountingPeriodInput input, AccessService.Access actor) {
    if (input == null) throw badRequest("请求体不能为空");
    var code = requireText(input.periodCode(), "期间编码");
    if (input.periodStart() == null || input.periodEnd() == null) {
      throw badRequest("期间起止日期不能为空");
    }
    if (input.periodEnd().isBefore(input.periodStart())) {
      throw badRequest("期间结束日不能早于开始日");
    }
    var currency = requireCurrency(input.baseCurrency());
    if (sql.fetchOne("select 1 from finance_voucher_period where period_code = ?", code) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "期间编码 " + code + " 已存在");
    }
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      sql.execute(
          "insert into finance_voucher_period(id,tenant_id,period_code,period_start,period_end,"
              + "status,base_currency,period_type,note,version,created_at,updated_at)"
              + " values(?,?,?,?,?,'OPEN',?,'CUSTOM',?,0,?::timestamptz,?::timestamptz)",
          id,
          actor.tenantId(),
          code,
          input.periodStart(),
          input.periodEnd(),
          currency,
          nz(input.note()),
          now,
          now);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "期间编码 " + code + " 已存在", e);
    }
    appendEvent(
        actor.tenantId(),
        id,
        "CREATED",
        "",
        "OPEN",
        "手工登记自定义期间 " + code,
        actor.userId(),
        null,
        "",
        "期间编码 " + code + "（自定义，不绑定会计日历）",
        now);
    return periodRow(requirePeriod(id));
  }

  /** 改期间备注（⚠️ 期间身份字段一律不可改 —— 见 {@link FinanceAccountingPeriodUpdateInput}）。 */
  @Transactional
  public FinanceAccountingPeriodRow update(
      UUID id, FinanceAccountingPeriodUpdateInput input, AccessService.Access actor) {
    if (input == null) throw badRequest("请求体不能为空");
    var existing = requirePeriodForUpdate(id);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_voucher_period set note = ?, version = version + 1,"
            + " updated_at = ?::timestamptz where id = ?",
        nz(input.note()),
        now,
        id);
    appendEvent(
        actor.tenantId(),
        id,
        "UPDATED",
        existing.get("status", String.class),
        existing.get("status", String.class),
        "修改期间备注",
        actor.userId(),
        null,
        "",
        "备注：" + nz(input.note()),
        now);
    return periodRow(requirePeriod(id));
  }

  /**
   * **关闭**一个会计期间。
   *
   * <p>🔴 必须写明关闭人 / 关闭时刻 / 关闭原因（DB 守卫钉死，服务层绕不过）。
   */
  @Transactional
  public FinanceAccountingPeriodRow close(
      UUID id, FinanceAccountingPeriodCloseInput input, AccessService.Access actor) {
    if (input == null) throw badRequest("请求体不能为空");
    var existing = requirePeriodForUpdate(id);
    var status = existing.get("status", String.class);
    if (!"OPEN".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有开放态的期间能关闭（当前：" + status + "）");
    }
    requireVersion(existing, input.expectedVersion());
    var reason = requireText(input.reason(), "关闭原因");
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_voucher_period set status='CLOSED', closed_at=?::timestamptz, closed_by=?,"
            + " closed_reason=?, version = version + 1, updated_at = ?::timestamptz where id = ?",
        now,
        actor.userId(),
        reason,
        now,
        id);
    // ⚠️ **关账记录由数据层写**（`guard_finance_voucher_period_trail` 触发器）：
    //    12.5.2.1 的过渡端点 `POST /finance/vouchers/periods/{id}/close` 也会关账，
    //    留痕只写在服务层的话，走那条路径关掉的期间就没有关账记录。
    return periodRow(requirePeriod(id));
  }

  /**
   * **重开**一个已关闭的会计期间 —— 蓝图加粗句「重开已关期间是高风险操作，必须审批留痕」。
   *
   * <p>🔴 <b>审批不可达就如实说不可达</b>：identity 读不到时本方法抛错、整个事务回滚、
   * 期间**保持关闭** —— 绝不「读不到就当批过了」。
   *
   * <p>四种结局（都如实报出来，不假装成功）：
   *
   * <ul>
   *   <li>{@code REOPEN_REQUESTED} —— 刚提交了审批申请，期间**仍然关闭**；
   *   <li>{@code PENDING_APPROVAL} —— 审批中，期间**仍然关闭**；
   *   <li>{@code REJECTED} —— 审批被驳回，期间**仍然关闭**；
   *   <li>{@code REOPENED} —— 审批已批准，期间**已重开**。
   * </ul>
   *
   * @param bearer 调用方自己的会话（转发给 identity，租户由那边按成员关系解析）
   */
  @Transactional
  public FinanceAccountingPeriodReopenResult reopen(
      UUID id, FinanceAccountingPeriodReopenInput input, AccessService.Access actor, String bearer) {
    if (input == null) throw badRequest("请求体不能为空");
    var existing = requirePeriodForUpdate(id);
    var status = existing.get("status", String.class);
    if (!"CLOSED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有已关闭的期间才能重开（当前：" + status + "）");
    }
    requireVersion(existing, input.expectedVersion());
    var reason = requireText(input.reason(), "重开原因");

    // ---- 1. 有没有在办的重开申请？ ----
    var request =
        sql.fetchOne(
            "select * from finance_accounting_period_event"
                + " where tenant_id = ? and period_id = ? and event_type = 'REOPEN_REQUESTED'"
                + " order by occurred_at desc, id desc limit 1",
            actor.tenantId(),
            id);
    var instanceId = request == null ? null : request.get("approval_instance_id", UUID.class);

    if (instanceId != null) {
      // 本地镜像里已有的结论优先（镜像只在 identity 明确说过之后才写 ⇒ 它就是证据）。
      var local = localApprovalDecision(actor.tenantId(), instanceId);
      if ("APPROVED".equals(local)) {
        return executeReopen(id, reason, instanceId, actor);
      }
      if (!"REJECTED".equals(local)) {
        // 结论还不确定 ⇒ 去 identity 读回链路（读不到 ⇒ 抛错，期间保持关闭）。
        var detail = approvals.record(instanceId, bearer);
        mirrorApproval(actor.tenantId(), id, detail, actor);
        var instanceStatus = detail.instance().status();
        if ("APPROVED".equals(instanceStatus)) {
          return executeReopen(id, reason, instanceId, actor);
        }
        if ("PENDING".equals(instanceStatus)) {
          return new FinanceAccountingPeriodReopenResult(
              "PENDING_APPROVAL",
              "重开申请已在审批中（当前停在节点「"
                  + nz(detail.instance().currentNodeName())
                  + "」）：审批通过后再次调用本端点即执行重开，期间**仍然关闭**。",
              instanceId,
              nz(detail.instance().instanceNo()),
              "PENDING",
              periodRow(requirePeriod(id)));
        }
        // 🔴 **被驳回就如实报被驳回** —— 不替用户悄悄换一次新申请（那会让「驳回」这件事消失）。
        //    下一次调用时本地镜像里已是 REJECTED ⇒ 那时才提交一次新的申请。
        return new FinanceAccountingPeriodReopenResult(
            "REJECTED",
            "重开申请被驳回（审批实例 "
                + instanceId
                + "）：期间**仍然关闭**。需要再申请请再次调用本端点。",
            instanceId,
            nz(detail.instance().instanceNo()),
            "REJECTED",
            periodRow(requirePeriod(id)));
      }
      // 本地已知「已被驳回」⇒ 落到下面提交一次新的申请。
    }

    // ---- 2. 提交一次新的重开申请 ----
    var flow = approvals.activeFlow(REOPEN_DOCUMENT_TYPE_CODE, bearer);
    var flowId = flow.flow() == null ? null : flow.flow().id();
    if (flowId == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "审批流没有 id，无法提交：请在 17.4.1.1 检查流程定义");
    }
    var code = existing.get("period_code", String.class);
    var submitted =
        approvals.submit(
            flowId,
            APPROVAL_SOURCE_SYSTEM,
            id.toString(),
            "重开会计期间 " + code,
            existing.get("base_currency", String.class),
            BigDecimal.ZERO,
            bearer);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    appendEvent(
        actor.tenantId(),
        id,
        "REOPEN_REQUESTED",
        "CLOSED",
        "CLOSED",
        reason,
        actor.userId(),
        submitted.id(),
        "PENDING",
        "已提交重开审批申请，实例 "
            + nz(submitted.instanceNo())
            + "，当前停在节点「"
            + nz(submitted.currentNodeName())
            + "」（路由依据："
            + nz(submitted.routedBy())
            + "）",
        now);
    return new FinanceAccountingPeriodReopenResult(
        "REOPEN_REQUESTED",
        "重开申请已提交审批（实例 "
            + nz(submitted.instanceNo())
            + "）：**审批通过前期间仍然关闭**，审批通过后再次调用本端点即执行重开。",
        submitted.id(),
        nz(submitted.instanceNo()),
        "PENDING",
        periodRow(requirePeriod(id)));
  }

  /** 执行重开（审批已批准）：改状态 + 写重开留痕 + 留痕事件。 */
  private FinanceAccountingPeriodReopenResult executeReopen(
      UUID id, String reason, UUID instanceId, AccessService.Access actor) {
    var existing = requirePeriodForUpdate(id);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_voucher_period set status='OPEN', reopened_at=?::timestamptz, reopened_by=?,"
            + " reopen_reason=?, reopen_approval_instance_id=?, reopen_count = reopen_count + 1,"
            + " version = version + 1, updated_at = ?::timestamptz where id = ?",
        now,
        actor.userId(),
        reason,
        instanceId,
        now,
        id);
    // ⚠️ **重开留痕同样由数据层写**（`guard_finance_voucher_period_trail`）。
    return new FinanceAccountingPeriodReopenResult(
        "REOPENED",
        "会计期间已重开（审批实例 " + instanceId + "）：期间恢复开放，可以继续过账。",
        instanceId,
        "",
        "APPROVED",
        periodRow(requirePeriod(id)));
  }

  /**
   * 本地镜像里这条审批实例的**结论**（APPROVED / REJECTED / 未知）。
   *
   * <p>⚠️ 镜像行只在 identity **明确**说过之后才写 ⇒ 它本身就是证据，可以直接采信。
   */
  private String localApprovalDecision(UUID tenantId, UUID instanceId) {
    var row =
        sql.fetchOne(
            "select"
                + " max(case when action = 'APPROVE' then 1 else 0 end) as approved,"
                + " max(case when action = 'REJECT' then 1 else 0 end) as rejected"
                + " from finance_accounting_period_approval"
                + " where tenant_id = ? and approval_instance_id = ?",
            tenantId,
            instanceId);
    if (row == null) return null;
    if (row.get("approved", Integer.class) != null && row.get("approved", Integer.class) > 0) {
      return "APPROVED";
    }
    if (row.get("rejected", Integer.class) != null && row.get("rejected", Integer.class) > 0) {
      return "REJECTED";
    }
    return null;
  }

  /** 把 identity 的审批链路镜像到本地（按 action_id 去重 ⇒ 反复同步不灌重复行）。 */
  private void mirrorApproval(
      UUID tenantId, UUID periodId, ApprovalPort.RecordDetail detail, AccessService.Access actor) {
    var instance = detail.instance();
    var timeline = detail.timeline() == null ? List.<ApprovalPort.ActionView>of() : detail.timeline();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    for (var action : timeline) {
      if (action.actionId() == null) continue;
      var exists =
          sql.fetchOne(
                  "select 1 from finance_accounting_period_approval"
                      + " where tenant_id = ? and approval_instance_id = ? and action_id = ?",
                  tenantId,
                  instance.id(),
                  action.actionId())
              != null;
      if (exists) continue;
      var at =
          action.decidedAt() == null
              ? now
              : ApprovalPort.toOffset(action.decidedAt());
      sql.execute(
          "insert into finance_accounting_period_approval(id,tenant_id,period_id,approval_instance_id,"
              + " approval_instance_no,action_id,action,node_key,node_name,node_order,actor_id,actor_name,"
              + " comment,decided_at,dwell_seconds,source_system,synced_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,'finance',?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          periodId,
          instance.id(),
          nz(instance.instanceNo()),
          action.actionId(),
          normalizeAction(action.action()),
          nz(action.nodeKey()),
          nz(action.nodeName()),
          action.nodeOrder(),
          action.actorId(),
          nz(action.actorName()),
          nz(action.comment()),
          at,
          action.dwellSeconds(),
          now);
    }
    // 🔴 **实例已批准但链路里没有单独的 APPROVE 动作**时，补一条（否则 DB 守卫会误判「没审批」）。
    //    这条留痕的依据是 identity 侧的**实例状态**，不是编的。
    if ("APPROVED".equals(instance.status())
        && localApprovalDecision(tenantId, instance.id()) == null) {
      sql.execute(
          "insert into finance_accounting_period_approval(id,tenant_id,period_id,approval_instance_id,"
              + " approval_instance_no,action_id,action,node_key,node_name,node_order,actor_id,actor_name,"
              + " comment,decided_at,dwell_seconds,source_system,synced_at)"
              + " values(?,?,?,?,?,null,'APPROVE','APPROVED','审批通过',0,null,'','"
              + "identity 侧实例状态为 APPROVED（链路里没有单独的 APPROVE 动作）',"
              + "?::timestamptz,0,'finance',?::timestamptz)",
          UUID.randomUUID(),
          tenantId,
          periodId,
          instance.id(),
          nz(instance.instanceNo()),
          instance.finishedAt() == null ? now : ApprovalPort.toOffset(instance.finishedAt()),
          now);
    }
  }

  // ================================================================ 会计日历

  /** 会计日历清单。 */
  public List<FinanceAccountingCalendarRow> calendars() {
    return sql.fetch("select * from finance_accounting_calendar order by calendar_code")
        .map(this::calendarRow);
  }

  /** 日历详情：日历 + 它生成的期间。 */
  public FinanceAccountingCalendarDetail calendarDetail(UUID id) {
    var calendar = requireCalendar(id);
    var periods =
        sql.fetch(
                "select p.*, c.calendar_code, c.calendar_name"
                    + " from finance_voucher_period p"
                    + " join finance_accounting_calendar c"
                    + "   on c.tenant_id = p.tenant_id and c.id = p.calendar_id"
                    + " where p.calendar_id = ? order by p.fiscal_year desc, p.fiscal_period_no",
                id)
            .map(this::periodRow);
    return new FinanceAccountingCalendarDetail(calendarRow(calendar), periods);
  }

  /** 建会计日历。 */
  @Transactional
  public FinanceAccountingCalendarRow createCalendar(
      FinanceAccountingCalendarInput input, AccessService.Access actor) {
    if (input == null) throw badRequest("请求体不能为空");
    var code = requireText(input.calendarCode(), "日历编码");
    var name = requireText(input.calendarName(), "日历名称");
    var type =
        input.calendarType() == null ? AccountingCalendarType.CUSTOM : input.calendarType();
    var startMonth =
        input.fiscalYearStartMonth() == null ? 1 : input.fiscalYearStartMonth();
    if (startMonth < 1 || startMonth > 12) {
      throw badRequest("财年起始月必须在 1..12 之间");
    }
    if (type == AccountingCalendarType.FISCAL_445 && input.anchorDate() == null) {
      throw badRequest("4-4-5 日历必须给出锚点日（财年起始日模板）");
    }
    var currency = requireCurrency(input.baseCurrency());
    if (sql.fetchOne("select 1 from finance_accounting_calendar where calendar_code = ?", code)
        != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "日历编码 " + code + " 已存在");
    }
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      sql.execute(
          "insert into finance_accounting_calendar(id,tenant_id,calendar_code,calendar_name,calendar_type,"
              + " fiscal_year_start_month,anchor_date,base_currency,status,note,version,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,'ACTIVE',?,0,?::timestamptz,?::timestamptz)",
          id,
          actor.tenantId(),
          code,
          name,
          type.name(),
          startMonth,
          input.anchorDate(),
          currency,
          nz(input.note()),
          now,
          now);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "日历编码 " + code + " 已存在", e);
    }
    return calendarRow(requireCalendar(id));
  }

  /** 改会计日历（⚠️ 只有名称 / 状态 / 备注可改 —— 账期形状不可改）。 */
  @Transactional
  public FinanceAccountingCalendarRow updateCalendar(
      UUID id, FinanceAccountingCalendarUpdateInput input, AccessService.Access actor) {
    if (input == null) throw badRequest("请求体不能为空");
    requireCalendar(id);
    var name = requireText(input.calendarName(), "日历名称");
    var status = input.status() == null ? AccountingCalendarStatus.ACTIVE : input.status();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_accounting_calendar set calendar_name = ?, status = ?, note = ?,"
            + " version = version + 1, updated_at = ?::timestamptz where id = ?",
        name,
        status.name(),
        nz(input.note()),
        now,
        id);
    return calendarRow(requireCalendar(id));
  }

  /**
   * 从会计日历**生成**一个财年的期间。
   *
   * <p>自然月：财年从 {@code fiscal_year_start_month} 起算 12 个日历月。
   * 4-4-5：锚点日的**月-日**是财年起始日模板，按请求的财年换算成那一年的一天，
   * 之后按 4-4-5 循环（28/28/35 天）× 4 轮 = 52 周推 12 个周期。
   *
   * <p>⚠️ **已存在的期间编码被跳过而不是覆盖** —— 用户手改过的期间不能被重跑冲掉。
   */
  @Transactional
  public FinanceAccountingPeriodGenerateResult generatePeriods(
      UUID calendarId, FinanceAccountingPeriodGenerateInput input, AccessService.Access actor) {
    if (input == null || input.fiscalYear() == null) {
      throw badRequest("必须指定要生成的财年");
    }
    var calendar = requireCalendar(calendarId);
    var type = AccountingCalendarType.valueOf(calendar.get("calendar_type", String.class));
    if (type == AccountingCalendarType.CUSTOM) {
      throw badRequest("自定义日历不生成期间：请直接手工登记自定义期间");
    }
    if (!"ACTIVE".equals(calendar.get("status", String.class))) {
      throw badRequest("日历已停用，不能再生成期间");
    }
    var fiscalYear = input.fiscalYear();
    if (fiscalYear < 1900 || fiscalYear > 2200) {
      throw badRequest("财年取值不合理：" + fiscalYear);
    }
    var baseCurrency = calendar.get("base_currency", String.class);
    var calendarCode = calendar.get("calendar_code", String.class);
    var spans = planPeriods(type, calendar, fiscalYear);

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var created = new ArrayList<FinanceAccountingPeriodRow>();
    var skipped = new ArrayList<String>();
    for (var span : spans) {
      if (sql.fetchOne("select 1 from finance_voucher_period where period_code = ?", span.code())
          != null) {
        skipped.add(span.code());
        continue;
      }
      var id = UUID.randomUUID();
      try {
        sql.execute(
            "insert into finance_voucher_period(id,tenant_id,period_code,period_start,period_end,"
                + "status,base_currency,calendar_id,fiscal_year,fiscal_period_no,period_type,note,"
                + "version,created_at,updated_at)"
                + " values(?,?,?,?,?,'OPEN',?,?,?,?,?,?,0,?::timestamptz,?::timestamptz)",
            id,
            actor.tenantId(),
            span.code(),
            span.start(),
            span.end(),
            baseCurrency,
            calendarId,
            fiscalYear,
            span.seq(),
            type.name(),
            "由会计日历 " + calendarCode + " 生成",
            now,
            now);
      } catch (DataIntegrityViolationException e) {
        skipped.add(span.code());
        continue;
      }
      appendEvent(
          actor.tenantId(),
          id,
          "CREATED",
          "",
          "OPEN",
          "由会计日历 " + calendarCode + " 生成财年 " + fiscalYear + " 的期间",
          actor.userId(),
          null,
          "",
          "期间编码 " + span.code() + "（" + type.label() + "，第 " + span.seq() + " 期）",
          now);
      created.add(periodRow(requirePeriod(id)));
    }
    return new FinanceAccountingPeriodGenerateResult(
        calendarId,
        fiscalYear,
        created.size(),
        skipped.size(),
        List.copyOf(skipped),
        List.copyOf(created));
  }

  /** 一个待生成的期间区间。 */
  private record Span(String code, LocalDate start, LocalDate end, int seq) {}

  /**
   * 按日历类型算出某个财年的 12 个期间区间。
   *
   * <p>⚠️ 4-4-5 的锚点日是**模板**：取它的月-日，换到请求的财年。
   * 2 月 29 日的锚点在非闰年无法换算 ⇒ 如实报错，不静默挪一天。
   */
  private List<Span> planPeriods(
      AccountingCalendarType type, Record calendar, int fiscalYear) {
    var calendarCode = calendar.get("calendar_code", String.class);
    var spans = new ArrayList<Span>();
    if (type == AccountingCalendarType.CALENDAR_MONTH) {
      var startMonth = calendar.get("fiscal_year_start_month", Integer.class);
      for (var seq = 1; seq <= 12; seq++) {
        var offset = startMonth - 1 + seq - 1;
        var year = fiscalYear + (offset >= 12 ? 1 : 0);
        var month = (offset % 12) + 1;
        var start = LocalDate.of(year, month, 1);
        var end = start.with(TemporalAdjusters.lastDayOfMonth());
        spans.add(new Span(codeOf(calendarCode, fiscalYear, seq), start, end, seq));
      }
      return spans;
    }
    // FISCAL_445
    var anchor = calendar.get("anchor_date", LocalDate.class);
    if (anchor == null) {
      throw badRequest("4-4-5 日历缺少锚点日，无法生成期间");
    }
    LocalDate start;
    try {
      start = LocalDate.of(fiscalYear, anchor.getMonthValue(), anchor.getDayOfMonth());
    } catch (java.time.DateTimeException e) {
      throw badRequest(
          "锚点日 "
              + anchor
              + " 在财年 "
              + fiscalYear
              + " 不存在（如 2 月 29 日遇非闰年）：请换一个锚点日");
    }
    var lengths = new int[] {28, 28, 35};
    var cursor = start;
    for (var seq = 1; seq <= 12; seq++) {
      var length = lengths[(seq - 1) % lengths.length];
      var end = cursor.plusDays(length - 1L);
      spans.add(new Span(codeOf(calendarCode, fiscalYear, seq), cursor, end, seq));
      cursor = cursor.plusDays(length);
    }
    return spans;
  }

  /** 期间编码：日历编码 + 财年 + 财年内序号（跨日历也不会撞）。 */
  private static String codeOf(String calendarCode, int fiscalYear, int seq) {
    return calendarCode + "-FY" + fiscalYear + "-" + String.format("%02d", seq);
  }

  // ================================================================ 留痕

  private void appendEvent(
      UUID tenantId,
      UUID periodId,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      UUID actorId,
      UUID approvalInstanceId,
      String approvalStatus,
      String detail,
      OffsetDateTime occurredAt) {
    sql.execute(
        "insert into finance_accounting_period_event(id,tenant_id,period_id,event_type,from_status,"
            + "to_status,reason,actor_id,approval_instance_id,approval_status,detail,occurred_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        periodId,
        eventType,
        fromStatus,
        toStatus,
        reason,
        actorId,
        approvalInstanceId,
        approvalStatus,
        detail,
        occurredAt);
  }

  // ================================================================ 取数与工具

  private Record requirePeriod(UUID id) {
    var row = sql.fetchOne(periodSelect() + " where p.id = ?", id);
    if (row == null) throw notFound("会计期间 " + id + " 不存在或无权查看");
    return row;
  }

  /** 锁住期间行（关账 / 重开都要先锁，避免并发双关 / 双开）。 */
  private Record requirePeriodForUpdate(UUID id) {
    // ⚠️ `FOR UPDATE OF p`：**外连接**的 nullable 侧不能被 FOR UPDATE 锁 ⇒ 必须点名锁 p。
    var row = sql.fetchOne(periodSelect() + " where p.id = ? for update of p", id);
    if (row == null) throw notFound("会计期间 " + id + " 不存在或无权查看");
    return row;
  }

  /**
   * 期间取数语句（**必须**带上日历的编码与名称）。
   *
   * <p>⚠️ 少了这个 LEFT JOIN，取出来的记录里就没有 {@code calendar_code}，
   * 而 {@link #periodRow} 会去读那一列 —— jOOQ 读不到列会抛
   * {@code IllegalArgumentException: Field 'calendar_code' not found in record}，
   * 那个异常会被统一错误处理吞成 400，现场只看到一句「字段格式不正确」。
   */
  private static String periodSelect() {
    return "select p.*, c.calendar_code, c.calendar_name"
        + " from finance_voucher_period p"
        + " left join finance_accounting_calendar c"
        + "   on c.tenant_id = p.tenant_id and c.id = p.calendar_id";
  }

  private Record requireCalendar(UUID id) {
    var row = sql.fetchOne("select * from finance_accounting_calendar where id = ?", id);
    if (row == null) throw notFound("会计日历 " + id + " 不存在或无权查看");
    return row;
  }

  private FinanceAccountingPeriodRow periodRow(Record r) {
    var status = AccountingPeriodStatus.valueOf(r.get("status", String.class));
    var type = AccountingPeriodType.valueOf(r.get("period_type", String.class));
    return new FinanceAccountingPeriodRow(
        r.get("id", UUID.class),
        r.get("period_code", String.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("period_length_days", Integer.class),
        status,
        status.label(),
        r.get("base_currency", String.class),
        type,
        type.label(),
        r.get("calendar_id", UUID.class),
        optString(r, "calendar_code"),
        optString(r, "calendar_name"),
        r.get("fiscal_year", Integer.class),
        r.get("fiscal_period_no", Integer.class),
        r.get("closed_at", OffsetDateTime.class),
        r.get("closed_by", UUID.class),
        nz(r.get("closed_reason", String.class)),
        r.get("reopened_at", OffsetDateTime.class),
        r.get("reopened_by", UUID.class),
        nz(r.get("reopen_reason", String.class)),
        r.get("reopen_approval_instance_id", UUID.class),
        r.get("reopen_count", Integer.class),
        nz(r.get("note", String.class)),
        r.get("version", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceAccountingCalendarRow calendarRow(Record r) {
    var type = AccountingCalendarType.valueOf(r.get("calendar_type", String.class));
    var status = AccountingCalendarStatus.valueOf(r.get("status", String.class));
    return new FinanceAccountingCalendarRow(
        r.get("id", UUID.class),
        r.get("calendar_code", String.class),
        r.get("calendar_name", String.class),
        type,
        type.label(),
        r.get("fiscal_year_start_month", Integer.class),
        r.get("anchor_date", LocalDate.class),
        r.get("base_currency", String.class),
        status,
        status.label(),
        nz(r.get("note", String.class)),
        r.get("version", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceAccountingPeriodEventRow eventRow(Record r) {
    var type = AccountingPeriodEventType.valueOf(r.get("event_type", String.class));
    return new FinanceAccountingPeriodEventRow(
        r.get("id", UUID.class),
        r.get("period_id", UUID.class),
        type,
        type.label(),
        nz(r.get("from_status", String.class)),
        nz(r.get("to_status", String.class)),
        nz(r.get("reason", String.class)),
        r.get("actor_id", UUID.class),
        r.get("approval_instance_id", UUID.class),
        nz(r.get("approval_status", String.class)),
        nz(r.get("detail", String.class)),
        r.get("occurred_at", OffsetDateTime.class));
  }

  private FinanceAccountingPeriodApprovalRow approvalRow(Record r) {
    return new FinanceAccountingPeriodApprovalRow(
        r.get("id", UUID.class),
        r.get("period_id", UUID.class),
        r.get("approval_instance_id", UUID.class),
        nz(r.get("approval_instance_no", String.class)),
        r.get("action_id", UUID.class),
        r.get("action", String.class),
        nz(r.get("node_key", String.class)),
        nz(r.get("node_name", String.class)),
        r.get("node_order", Integer.class),
        r.get("actor_id", UUID.class),
        nz(r.get("actor_name", String.class)),
        nz(r.get("comment", String.class)),
        r.get("decided_at", OffsetDateTime.class),
        r.get("dwell_seconds", Long.class),
        r.get("synced_at", OffsetDateTime.class));
  }

  private void requireVersion(Record row, Integer expectedVersion) {
    if (expectedVersion == null) {
      throw badRequest("必须携带 expected_version（乐观并发）：期间状态变更不允许静默覆盖");
    }
    var actual = row.get("version", Integer.class);
    if (!actual.equals(expectedVersion)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "会计期间已被他人修改（版本 " + actual + " ≠ " + expectedVersion + "），请刷新后重试");
    }
  }

  private static String requireText(String raw, String label) {
    var value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) throw badRequest(label + "不能为空");
    if (value.length() > 500) throw badRequest(label + "长度不能超过 500 个字符");
    return value;
  }

  private static String requireCurrency(String raw) {
    var value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if (!value.matches("^[A-Z]{3}$")) {
      throw badRequest("本位币必须是三位大写字母（如 USD）");
    }
    return value;
  }

  /** identity 的动作词表归一（上游加词也不该把本模块撑爆）。 */
  private static String normalizeAction(String action) {
    if (action == null) return "";
    return switch (action.trim().toUpperCase(Locale.ROOT)) {
      case "APPROVE" -> "APPROVE";
      case "REJECT" -> "REJECT";
      case "TRANSFER" -> "TRANSFER";
      default -> "SUBMIT";
    };
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  /**
   * 读一个**可能不存在**的列。
   *
   * <p>⚠️ jOOQ 的 {@code get(String)} 读不到列会抛 {@code IllegalArgumentException}，
   * 那个异常会被统一错误处理吞成 400，现场只看到「字段格式不正确」——
   * 比真实原因更难查。可选列一律走这里。
   */
  private static String optString(Record r, String field) {
    return r.field(field) == null ? null : r.get(field, String.class);
  }

  private static Object[] concat(List<Object> binds, Object... extra) {
    var all = new ArrayList<>(binds);
    all.addAll(List.of(extra));
    return all.toArray();
  }

  private static ResponseStatusException badRequest(String detail) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
  }

  private static ResponseStatusException notFound(String detail) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, detail);
  }
}
