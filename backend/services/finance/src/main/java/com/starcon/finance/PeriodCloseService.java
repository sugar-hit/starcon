package com.starcon.finance;

import static com.starcon.finance.PeriodCloseModels.*;

import com.starcon.finance.AccountingPeriodModels.FinanceAccountingPeriodCloseInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.5.1.3 关账。
 *
 * <p>蓝图 12-finance.md：「期末关账流程：检查未处理事项（未对账差异、未结转成本、未核销款项、
 * 未过账凭证），按已批准清单区分阻断项与可结转未清项后关闭期间；禁止向已关闭期间录入业务凭证。」
 * 输入：「关账检查清单结果；关账确认。」输出：「关账完成记录；期间锁定。」
 * 关系：「上游 = 12.5.1.2；下游 = 12.5.2.2 报表。**关账检查清单是防错最后一道关**：
 * 阻断项未解决不允许关账；正常未到期应收应付不要求清零，例外与结转须保留依据。」
 *
 * <p><b>本模块的七条设计主线</b>：
 *
 * <ol>
 *   <li><b>职责边界：12.5.1.3 拥有「关账流程」，12.5.1.2 拥有「期间记录与状态机」。</b>
 *       本模块**只**建自己的表（清单 / 检查项 / 明细 / 金额分组 / 执行记录 / 审批镜像），
 *       **读** {@code finance_voucher_period}；关账这个动作**调 12.5.1.2 已有的关闭能力**
 *       （{@link AccountingPeriodService#close}），**不重建**期间状态机、**不实现**重开、
 *       **不新建**平行的期间表。</li>
 *   <li><b>检查清单真查数据。</b>四类检查项各自对应真实数据源：
 *       未结转成本 → {@code finance_cost_calculation_run}（12.4.1.2）；
 *       未核销款项 → {@code finance_receivable} / {@code finance_receipt} /
 *       {@code finance_settlement}（12.1.1.1 / 12.2.1.1 / 12.3.1.1）；
 *       未过账凭证 → {@code finance_voucher} 的 DRAFT（12.5.2.1）。
 *       每项都落**具体条数**与**可钻取明细**。</li>
 *   <li><b>未对账差异如实报「不可判定」。</b>上游 12.3.2.1 / 12.3.2.2 <b>未交付</b> ⇒
 *       {@code result = NOT_DETERMINABLE}、{@code classification = CARRYWARD}，
 *       <b>不得</b>假装通过、<b>不得</b>把关账卡死在一个永远满足不了的条件上。</li>
 *   <li><b>阻断项 vs 可结转未清项。</b>规则判定（{@code ruleClassification}）与最终分类
 *       （{@code classification}）分开存；人工把阻断项下调为可结转项**必须**写例外依据
 *       （服务层 + DB 守卫各拒一次）。</li>
 *   <li><b>关账走 17 审批留痕。</b>单据类型码是**本模块自己的** {@code PERIOD_CLOSE}
 *       （不复用付款单的 {@code PAYMENT_ORDER}）；审批不可达 ⇒ 如实报不可达。</li>
 *   <li><b>「已关闭期间禁止过账」不重复实现。</b>那道守卫是 V357 的
 *       {@code guard_finance_voucher_post_period}；本模块只在关账结果里如实反映
 *       「该期间已锁定、禁止过账」。</li>
 *   <li><b>多币种绝不相加。</b>金额按币种分组落库（唯一键含 currency），
 *       本位币折算取不到汇率就<b>只给分组、不折算</b>。</li>
 * </ol>
 */
@Service
public class PeriodCloseService {

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 本模块在 identity 侧登记的关账单据类型码。
   *
   * <p>⇒ 租户要给自己的关账配审批流，就在 17.4.1.1 里注册一个
   * {@code document_type_code = 'PERIOD_CLOSE'} 的流程并置为 ACTIVE。
   *
   * <p>⚠️ **没有注册审批流 ⇒ 拒绝关账**（不是「默认放行」）：关账是不可逆的期末操作。
   */
  public static final String DOCUMENT_TYPE_CODE = "PERIOD_CLOSE";

  /** 审批提交时的来源系统（恒为 finance：本模块是提交方）。 */
  private static final String APPROVAL_SOURCE_SYSTEM = "finance";

  /** 期间锁定说明（如实反映 V357 的过账守卫，不重复实现它）。 */
  static final String PERIOD_LOCK_NOTE =
      "该期间已锁定、禁止过账（12.5.2.1 的 guard_finance_voucher_post_period 触发器在过账那一刻"
          + "读会计期间状态，期间不是 OPEN 就写不进去）";

  private final DSLContext sql;
  private final ApprovalPort approvals;
  private final AccountingPeriodService periods;
  private final ExchangeRatePort exchangeRates;

  public PeriodCloseService(
      DSLContext sql,
      ApprovalPort approvals,
      AccountingPeriodService periods,
      ExchangeRatePort exchangeRates) {
    this.sql = sql;
    this.approvals = approvals;
    this.periods = periods;
    this.exchangeRates = exchangeRates;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>🔴 未对账差异这一项**必须**单独报成缺口 —— 它的上游（12.3.2.1 / 12.3.2.2）**未交付**，
   * 本模块**如实报「不可判定」**，不假装通过。
   */
  public FinancePeriodCloseCapabilities capabilities() {
    var capabilities = new ArrayList<FinancePeriodCloseCapability>();
    var gaps = new ArrayList<FinancePeriodCloseDownstreamGap>();

    capabilities.add(
        new FinancePeriodCloseCapability(
            "12.5.1.3",
            "关账检查清单（四类未处理事项真查数据）",
            true,
            "finance_period_close_checklist / _check / _check_item / _check_amount",
            "",
            "蓝图职责写死「检查未处理事项（未对账差异、未结转成本、未核销款项、未过账凭证）」："
                + "四类检查项**各自查各自的真实数据源** —— 未结转成本查 12.4.1.2 的"
                + " finance_cost_calculation_run、未核销款项查 12.1.1.1 / 12.2.1.1 / 12.3.1.1 的"
                + " 应收 / 收款 / 结算、未过账凭证查 12.5.2.1 的 finance_voucher（DRAFT）。"
                + "🔴 **每一项都给出具体条数与可钻取明细**，不是「有 / 没有」的勾选框。"));

    capabilities.add(
        new FinancePeriodCloseCapability(
            "12.5.1.3",
            "阻断项 / 可结转未清项分类与依据落库",
            true,
            "finance_period_close_check.rule_classification / classification /"
                + " classification_basis / exception_basis",
            "",
            "蓝图加粗句「阻断项未解决不允许关账；正常未到期应收应付不要求清零，"
                + "**例外与结转须保留依据**」落成结构："
                + "① **规则判定**与**最终分类**分两列存（规则判定不可改）；"
                + "② 人工把阻断项下调为可结转未清项**必须**写例外依据 —— "
                + "服务层 400 + DB 守卫各拒一次，直连 SQL 也写不出「没有依据的例外」；"
                + "③ 「正常未到期应收应付」在规则层就归为可结转未清项，不要求清零。"));

    capabilities.add(
        new FinancePeriodCloseCapability(
            "12.5.1.3",
            "关账执行与关账完成记录（审批留痕）",
            true,
            "finance_period_close_execution / _approval + 17 审批（identity）",
            "",
            "关账是**不可逆的期末操作** ⇒ 必须审批留痕。"
                + "本模块走 17 审批（identity）的授权 HTTP API，单据类型码是**本模块自己的**"
                + " PERIOD_CLOSE（不复用付款单的 PAYMENT_ORDER）；"
                + "🔴 **没有注册生效审批流 ⇒ 拒绝关账**；"
                + "**审批服务不可达 ⇒ 整个调用失败、期间保持开放** —— 绝不「读不到就当批过了」。"
                + "审批实例与逐步动作镜像进本地，可从关账记录钻取到。"));

    capabilities.add(
        new FinancePeriodCloseCapability(
            "12.5.1.2",
            "会计期间（读 + 走它的关闭能力）",
            true,
            "finance_voucher_period（12.5.1.2 已接管）",
            "",
            "本模块**不新建平行的期间表**、**不重新实现期间状态机**、**不实现重开**："
                + "关账动作直接调 12.5.1.2 的 AccountingPeriodService.close(...) ⇒ "
                + "关账留痕（finance_accounting_period_event）由它那侧的触发器写，"
                + "本模块**一个字都没改**别人的表。"));

    capabilities.add(
        new FinancePeriodCloseCapability(
            "12.5.2.1",
            "已关闭期间禁止过账（**由 12.5.2.1 的触发器生效**）",
            true,
            "V357 的 guard_finance_voucher_post_period（读 finance_voucher_period.status）",
            "",
            "蓝图「禁止向已关闭期间录入业务凭证」的那道闸门**已经生效** —— "
                + "本模块**不重复实现、不改语义**，只在关账结果里如实反映"
                + "「该期间已锁定、禁止过账」。"));

    capabilities.add(
        new FinancePeriodCloseCapability(
            "12.3.2.1",
            "未对账差异（**上游未交付 ⇒ 不可判定**）",
            false,
            "本模块无数据源（12.3.2.1 对账任务 / 12.3.2.2 差异处理未开发）",
            "12.3.2.1",
            "🔴 **如实报「上游未交付 / 不可判定」**（result=NOT_DETERMINABLE），"
                + "**不得**假装通过、**不得**静默当成通过。"
                + "它归入「可结转未清项」并显式标注为不可判定 —— "
                + "把关账卡死在一个永远无法满足的条件上，那不是防错，是把功能做成死路。"));

    gaps.add(
        new FinancePeriodCloseDownstreamGap(
            "12.3.2.1",
            "对账任务",
            "finance",
            false,
            "12.3 结算与对账",
            "未对账差异检查项的数据源未交付 ⇒ 本模块如实报「不可判定」，"
                + "不假装通过。"));

    gaps.add(
        new FinancePeriodCloseDownstreamGap(
            "12.3.2.2",
            "差异处理",
            "finance",
            false,
            "12.3 结算与对账",
            "差异处理未交付 ⇒ 对账差异的例外与结转依据只能由租户在本模块里手写，"
                + "系统无法自动判定哪条差异可以放行。"));

    gaps.add(
        new FinancePeriodCloseDownstreamGap(
            "12.5.2.2",
            "财务报表",
            "finance",
            false,
            "12.5 凭证与报表",
            "报表是本模块的**下游**（关账完成后才出报表）：本模块**不依赖**它、"
                + "**不读**它的表，也**不因为它没到而阻断关账**。"));

    return new FinancePeriodCloseCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinancePeriodCloseOptions options() {
    var statuses =
        sql.fetch("select distinct status from finance_period_close_execution order by status")
            .map(r -> r.get(0, String.class));
    var currencies =
        sql.fetch("select distinct currency from finance_period_close_check_amount order by currency")
            .map(r -> r.get(0, String.class));
    var periodStatuses =
        sql.fetch("select distinct status from finance_voucher_period order by status")
            .map(r -> r.get(0, String.class));
    var periods =
        sql.fetch(
                "select id, period_code, period_start, period_end, status"
                    + " from finance_voucher_period order by period_start desc limit 200")
            .map(
                r ->
                    new FinancePeriodCloseOptionRef(
                        r.get("id", UUID.class).toString(),
                        r.get("period_code", String.class),
                        r.get("period_code", String.class)
                            + "（"
                            + r.get("period_start", LocalDate.class)
                            + " ~ "
                            + r.get("period_end", LocalDate.class)
                            + "）",
                        r.get("status", String.class)));
    var checkCodes = new ArrayList<String>();
    for (var code : PeriodCloseCheckCode.values()) checkCodes.add(code.name());
    return new FinancePeriodCloseOptions(statuses, checkCodes, currencies, periodStatuses, periods);
  }

  // ================================================================ 检查清单

  /** 清单列表：真分页 + 真筛选。 */
  public FinancePeriodCloseChecklistPage pageChecklists(
      UUID periodId, String keyword, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (periodId != null) {
      where.add("c.period_id = ?");
      binds.add(periodId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(c.checklist_no ilike ? or c.note ilike ?)");
      var like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from finance_period_close_checklist c" + clause, binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select c.*, p.period_code, p.status as period_status"
                    + " from finance_period_close_checklist c"
                    + " join finance_voucher_period p"
                    + "   on p.tenant_id = c.tenant_id and p.id = c.period_id"
                    + clause
                    + " order by c.created_at desc, c.id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::checklistRow);
    return new FinancePeriodCloseChecklistPage(items, p, s, total);
  }

  /**
   * **跑一次关账检查清单**（真查数据）。
   *
   * <p>⚠️ 清单**只追加**：重跑一次多一份证据（run_no 递增），已跑过的清单一个字都不改。
   */
  @Transactional
  public FinancePeriodCloseChecklistDetail runChecklist(
      FinancePeriodCloseChecklistInput input, AccessService.Access actor, String bearer) {
    if (input == null || input.periodId() == null) throw badRequest("必须指定要检查的会计期间");
    var period = requirePeriodForUpdate(input.periodId());
    var status = period.get("status", String.class);
    if (!"OPEN".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有开放态的期间能跑关账检查清单（当前：" + status + "）");
    }
    var window = new PeriodWindow(period);
    var now = OffsetDateTime.now(ZoneOffset.UTC);

    var runNo =
        sql.fetchOne(
                "select coalesce(max(run_no), 0) + 1 from finance_period_close_checklist"
                    + " where period_id = ?",
                period.get("id", UUID.class))
            .get(0, Long.class)
            .intValue();
    var checklistId = UUID.randomUUID();
    var checklistNo =
        "CL-" + period.get("period_code", String.class) + "-" + String.format("%04d", runNo);
    sql.execute(
        "insert into finance_period_close_checklist(id,tenant_id,period_id,run_no,checklist_no,"
            + "period_start,period_end,base_currency,started_at,completed_at,created_by,note,created_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?::timestamptz)",
        checklistId,
        actor.tenantId(),
        period.get("id", UUID.class),
        runNo,
        checklistNo,
        window.startDate(),
        window.endDate(),
        period.get("base_currency", String.class),
        now,
        now,
        actor.userId(),
        nz(input.note()),
        now);

    // ---- 四类检查项：各自查各自的真实数据源 ----
    var computed = new ArrayList<ComputedCheck>();
    computed.add(computeUnreconciledDifference());
    computed.add(computeUncarriedCost(window));
    computed.add(computeUnsettledPayment(window));
    computed.add(computeUnpostedVoucher(window));

    for (var check : computed) {
      var checkId = UUID.randomUUID();
      sql.execute(
          "insert into finance_period_close_check(id,tenant_id,checklist_id,period_id,check_code,"
              + "check_name,rule_classification,classification,result,classification_basis,"
              + "exception_basis,item_count,data_source,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,'',?,?,?::timestamptz)",
          checkId,
          actor.tenantId(),
          checklistId,
          period.get("id", UUID.class),
          check.code().name(),
          check.name(),
          check.ruleClassification().name(),
          check.ruleClassification().name(),
          check.result().name(),
          check.basis(),
          check.items().size(),
          check.dataSource(),
          now);
      for (var item : check.items()) {
        sql.execute(
            "insert into finance_period_close_check_item(id,tenant_id,checklist_id,check_id,period_id,"
                + "source_module,source_type,source_id,source_no,currency,amount,quantity,reason,"
                + "classification,due_date,created_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
            UUID.randomUUID(),
            actor.tenantId(),
            checklistId,
            checkId,
            period.get("id", UUID.class),
            item.sourceModule(),
            item.sourceType(),
            item.sourceId(),
            item.sourceNo(),
            item.currency(),
            item.amount(),
            item.quantity(),
            item.reason(),
            item.classification().name(),
            item.dueDate(),
            now);
      }
      // 金额**按币种分组**落库：结构上就不存在「跨币种求和」这一格。
      for (var group : groupByCurrency(check.items())) {
        sql.execute(
            "insert into finance_period_close_check_amount(id,tenant_id,checklist_id,check_id,"
                + "currency,amount,item_count,created_at)"
                + " values(?,?,?,?,?,?,?,?::timestamptz)",
            UUID.randomUUID(),
            actor.tenantId(),
            checklistId,
            checkId,
            group.currency(),
            group.amount(),
            group.itemCount(),
            now);
      }
    }
    return buildDetail(checklistId, bearer);
  }

  /** 清单详情：清单 + 四类检查项 + 按币种的金额分组。 */
  public FinancePeriodCloseChecklistDetail checklistDetail(UUID checklistId, String bearer) {
    return buildDetail(checklistId, bearer);
  }

  private FinancePeriodCloseChecklistDetail buildDetail(UUID checklistId, String bearer) {
    var checklist = requireChecklist(checklistId);
    var amounts = amounts(checklistId);
    var checks = checks(checklistId, amounts);
    var period = requirePeriod(checklist.periodId());
    var groups =
        currencyGroups(
            checklistCurrencyTotals(amounts),
            checklist.baseCurrency(),
            checklist.periodEnd(),
            bearer == null ? "" : bearer);
    int blocking = 0;
    int carryforward = 0;
    int notDeterminable = 0;
    int passed = 0;
    for (var check : checks) {
      if (check.result() == PeriodCloseCheckResult.PASS) passed++;
      else if (check.result() == PeriodCloseCheckResult.NOT_DETERMINABLE) notDeterminable++;
      else if (check.classification() == PeriodCloseClassification.BLOCKING) blocking++;
      else carryforward++;
    }
    var periodStatus = period.get("status", String.class);
    return new FinancePeriodCloseChecklistDetail(
        checklist,
        checks,
        amounts,
        groups,
        checks.size(),
        blocking,
        carryforward,
        notDeterminable,
        passed,
        periodStatus,
        periodStatusLabel(periodStatus),
        "CLOSED".equals(periodStatus)
            ? PERIOD_LOCK_NOTE
            : "该期间当前开放，可以过账；关账执行后由 12.5.2.1 的过账守卫拦住向本期的过账");
  }

  /** 明细钻取：真分页。 */
  public FinancePeriodCloseCheckItemPage items(UUID checklistId, UUID checkId, int page, int size) {
    requireChecklist(checklistId);
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    var where = " where checklist_id = ?";
    var binds = new ArrayList<Object>();
    binds.add(checklistId);
    if (checkId != null) {
      where += " and check_id = ?";
      binds.add(checkId);
    }
    long total =
        sql.fetchOne("select count(*) from finance_period_close_check_item" + where, binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select * from finance_period_close_check_item"
                    + where
                    + " order by check_id, currency, id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::itemRow);
    return new FinancePeriodCloseCheckItemPage(items, p, s, total);
  }

  /**
   * 把一个**阻断项**下调为**可结转未清项**（例外）。
   *
   * <p>🔴 **必须写例外依据** —— 蓝图加粗句「例外与结转须保留依据」。
   * 服务层 400 + DB 守卫各拒一次。
   */
  @Transactional
  public FinancePeriodCloseCheckRow carryForward(
      UUID checklistId, UUID checkId, FinancePeriodCloseCarryForwardInput input,
      AccessService.Access actor) {
    if (input == null) throw badRequest("请求体不能为空");
    var basis = requireText(input.exceptionBasis(), "例外依据");
    requireChecklist(checklistId);
    var check = requireCheck(checkId);
    if (!checklistId.equals(check.checklistId())) {
      throw badRequest("该检查项不属于这份检查清单");
    }
    if (check.ruleClassification() != PeriodCloseClassification.BLOCKING) {
      throw badRequest("只有规则判定为阻断项的检查项才能下调为可结转未清项");
    }
    if (check.classification() != PeriodCloseClassification.BLOCKING) {
      throw badRequest("该检查项已经是可结转未清项，不能重复下调");
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_period_close_check set classification = 'CARRYWARD', exception_basis = ?,"
            + " exception_by = ?, exception_at = ?::timestamptz where id = ?",
        basis,
        actor.userId(),
        now,
        checkId);
    return requireCheck(checkId);
  }

  // ================================================================ 关账执行

  /** 关账执行记录列表：真分页 + 真筛选。 */
  public FinancePeriodCloseExecutionPage pageExecutions(UUID periodId, String status, int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (periodId != null) {
      where.add("e.period_id = ?");
      binds.add(periodId);
    }
    if (status != null && !status.isBlank()) {
      where.add("e.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from finance_period_close_execution e" + clause, binds.toArray())
            .get(0, Long.class);
    var items =
        sql.fetch(
                "select e.*, p.period_code, c.checklist_no from finance_period_close_execution e"
                    + " join finance_voucher_period p"
                    + "   on p.tenant_id = e.tenant_id and p.id = e.period_id"
                    + " join finance_period_close_checklist c"
                    + "   on c.tenant_id = e.tenant_id and c.id = e.checklist_id"
                    + clause
                    + " order by e.created_at desc, e.id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::executionRow);
    return new FinancePeriodCloseExecutionPage(items, p, s, total);
  }

  /** 关账执行详情：执行记录 + 审批链路 + 据以关账的那份清单。 */
  public FinancePeriodCloseExecutionDetail executionDetail(UUID executionId, String bearer) {
    var execution = requireExecution(executionId);
    return new FinancePeriodCloseExecutionDetail(
        execution, approvals(executionId), buildDetail(execution.checklistId(), bearer));
  }

  /**
   * **提交关账**（走审批）。
   *
   * <p>🔴 **阻断项未解决 ⇒ 拒绝关账**，返回明确的阻断原因清单
   * （{@code outcome = "BLOCKED"}，HTTP 409，阻断项一条不少）。
   *
   * <p>四种结局（都如实报出来，不假装成功）：
   *
   * <ul>
   *   <li>{@code BLOCKED} —— 有阻断项未解决，**期间仍然开放**；
   *   <li>{@code PENDING_APPROVAL} —— 已提交审批申请，**期间仍然开放**。
   * </ul>
   *
   * @param bearer 调用方自己的会话（转发给 identity，租户由那边按成员关系解析）
   */
  @Transactional
  public FinancePeriodCloseSubmitResult submit(
      FinancePeriodCloseExecuteInput input, AccessService.Access actor, String bearer) {
    if (input == null || input.checklistId() == null) {
      throw badRequest("必须指定要据以关账的检查清单");
    }
    var checklist = requireChecklist(input.checklistId());
    var period = requirePeriod(checklist.periodId());
    var periodStatus = period.get("status", String.class);
    if (!"OPEN".equals(periodStatus)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "期间已不是开放态（当前：" + periodStatus + "），不能关账");
    }
    var checks = checks(checklist.id());

    // ---- ① 阻断项未解决 ⇒ 拒绝关账 ----
    var blocking = new ArrayList<FinancePeriodCloseCheckRow>();
    for (var check : checks) {
      if (check.classification() == PeriodCloseClassification.BLOCKING) blocking.add(check);
    }
    if (!blocking.isEmpty()) {
      var reasons = new ArrayList<FinancePeriodCloseBlockingReason>();
      for (var check : blocking) {
        reasons.add(
            new FinancePeriodCloseBlockingReason(
                check.checkCode(), check.checkName(), check.itemCount(), check.classificationBasis()));
      }
      var names = new ArrayList<String>();
      for (var check : blocking) {
        names.add(check.checkName() + "（" + check.itemCount() + " 条）");
      }
      return new FinancePeriodCloseSubmitResult(
          "BLOCKED",
          "存在 " + blocking.size() + " 项阻断项未解决，不允许关账：" + String.join("、", names)
              + "。请先处理，或把确需结转的项下调为可结转未清项并写明例外依据。",
          reasons,
          null);
    }

    // ---- ② 有可结转未清项 ⇒ 必须写结转依据 ----
    int carryforwardCount = 0;
    int notDeterminableCount = 0;
    for (var check : checks) {
      if (check.result() == PeriodCloseCheckResult.NOT_DETERMINABLE) notDeterminableCount++;
      else if (check.result() == PeriodCloseCheckResult.FAIL) carryforwardCount++;
    }
    var carryforwardBasis = nz(input.carryforwardBasis());
    if (carryforwardCount > 0 && carryforwardBasis.isBlank()) {
      throw badRequest(
          "本期有 " + carryforwardCount + " 项可结转未清项，必须写明结转依据"
              + "（蓝图：例外与结转须保留依据）");
    }
    var reason = requireText(input.reason(), "关账原因");

    // ---- ③ 提交审批（没有生效审批流 ⇒ 拒绝关账）----
    var flow = approvals.activeFlow(DOCUMENT_TYPE_CODE, bearer);
    var flowId = flow.flow() == null ? null : flow.flow().id();
    if (flowId == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "审批流没有 id，无法提交：请在 17.4.1.1 检查流程定义");
    }
    var executionId = UUID.randomUUID();
    var closeNo =
        "PC-" + checklist.periodCode() + "-" + String.format("%04d", checklist.runNo());
    var submitted =
        approvals.submit(
            flowId,
            APPROVAL_SOURCE_SYSTEM,
            executionId.toString(),
            "关账 " + checklist.periodCode() + "（" + checklist.periodStart() + " ~ "
                + checklist.periodEnd() + "）",
            checklist.baseCurrency(),
            BigDecimal.ZERO,
            bearer);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_period_close_execution(id,tenant_id,period_id,checklist_id,close_no,"
            + "status,blocking_count,carryforward_count,not_determinable_count,carryforward_basis,"
            + "reason,approval_instance_id,approval_instance_no,approval_status,period_locked,"
            + "lock_note,created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,'PENDING_APPROVAL',0,?,?,?,?,?,?,'PENDING',false,'',?,"
            + "?::timestamptz,?::timestamptz)",
        executionId,
        actor.tenantId(),
        checklist.periodId(),
        checklist.id(),
        closeNo,
        carryforwardCount,
        notDeterminableCount,
        carryforwardBasis,
        reason,
        submitted.id(),
        nz(submitted.instanceNo()),
        actor.userId(),
        now,
        now);
    return new FinancePeriodCloseSubmitResult(
        "PENDING_APPROVAL",
        "关账申请已提交审批（实例 " + nz(submitted.instanceNo()) + "，当前停在节点「"
            + nz(submitted.currentNodeName()) + "」）：**审批通过前期间仍然开放**，"
            + "审批通过后同步审批结果即执行关账，期间即锁定、禁止过账。",
        List.of(),
        executionId.toString());
  }

  /**
   * **同步审批结果**（读回 17 审批的链路）；审批已批准 ⇒ 真的执行关账。
   *
   * <p>🔴 **审批不可达 ⇒ 整个调用失败、期间保持开放** —— 绝不「读不到就当批过了」。
   */
  @Transactional
  public FinancePeriodCloseExecutionDetail syncApproval(
      UUID executionId, AccessService.Access actor, String bearer) {
    var execution = requireExecution(executionId);
    if (execution.status() == PeriodCloseExecutionStatus.CLOSED) {
      return executionDetail(executionId, bearer);
    }
    var instanceId = execution.approvalInstanceId();
    if (instanceId == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这次关账还没有审批实例，无法同步");
    }
    var detail = approvals.record(instanceId, bearer);
    mirrorApproval(execution, detail, actor);
    var instanceStatus = detail.instance().status();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    if ("APPROVED".equals(instanceStatus)) {
      if (execution.status() == PeriodCloseExecutionStatus.REJECTED) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "这次关账申请已被驳回，不能再执行关账（请重新跑一份检查清单再提交）");
      }
      executeClose(execution, actor, now);
    } else if ("REJECTED".equals(instanceStatus)) {
      sql.execute(
          "update finance_period_close_execution set status = 'REJECTED', approval_status = 'REJECTED',"
              + " updated_at = ?::timestamptz where id = ?",
          now,
          executionId);
    } else {
      sql.execute(
          "update finance_period_close_execution set status = 'PENDING_APPROVAL',"
              + " approval_status = 'PENDING', updated_at = ?::timestamptz where id = ?",
          now,
          executionId);
    }
    return executionDetail(executionId, bearer);
  }

  /** 执行关账：走 12.5.1.2 已有的期间关闭能力（**不重建期间状态机**）。 */
  private void executeClose(
      FinancePeriodCloseExecutionRow execution, AccessService.Access actor, OffsetDateTime now) {
    var period = requirePeriodForUpdate(execution.periodId());
    var periodStatus = period.get("status", String.class);
    if (!"OPEN".equals(periodStatus)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "期间已不是开放态（当前：" + periodStatus + "），关账未执行");
    }
    var version = period.get("version", Integer.class);
    // 🔴 **关账动作本身走 12.5.1.2 的能力** —— 它的守卫与留痕照旧生效，
    //    本模块不自己往 `finance_voucher_period` 写状态。
    periods.close(
        execution.periodId(),
        new FinanceAccountingPeriodCloseInput(closeReason(execution), version),
        actor);
    sql.execute(
        "update finance_period_close_execution set status = 'CLOSED', approval_status = 'APPROVED',"
            + " period_locked = true, lock_note = ?, closed_at = ?::timestamptz, closed_by = ?,"
            + " updated_at = ?::timestamptz where id = ?",
        PERIOD_LOCK_NOTE,
        now,
        actor.userId(),
        now,
        execution.id());
  }

  /** 关账原因（写进 12.5.1.2 的关账留痕里，让人一眼看到这次是带结转关的）。 */
  private static String closeReason(FinancePeriodCloseExecutionRow execution) {
    var reason = "关账 " + execution.closeNo() + "：" + execution.reason();
    if (!execution.carryforwardBasis().isBlank()) {
      reason = reason + "；结转依据：" + execution.carryforwardBasis();
    }
    return reason.length() > 500 ? reason.substring(0, 500) : reason;
  }

  /** 把 identity 的审批链路镜像到本地（按 action_id 去重 ⇒ 反复同步不灌重复行）。 */
  private void mirrorApproval(
      FinancePeriodCloseExecutionRow execution,
      ApprovalPort.RecordDetail detail,
      AccessService.Access actor) {
    var instance = detail.instance();
    var timeline = detail.timeline() == null ? List.<ApprovalPort.ActionView>of() : detail.timeline();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    for (var action : timeline) {
      if (action.actionId() == null) continue;
      var exists =
          sql.fetchOne(
                  "select 1 from finance_period_close_approval"
                      + " where tenant_id = ? and approval_instance_id = ? and action_id = ?",
                  actor.tenantId(),
                  instance.id(),
                  action.actionId())
              != null;
      if (exists) continue;
      var at = action.decidedAt() == null ? now : ApprovalPort.toOffset(action.decidedAt());
      sql.execute(
          "insert into finance_period_close_approval(id,tenant_id,execution_id,period_id,"
              + "approval_instance_id,approval_instance_no,action_id,action,node_key,node_name,"
              + "node_order,actor_id,actor_name,comment,decided_at,dwell_seconds,source_system,"
              + "synced_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,'finance',?::timestamptz)",
          UUID.randomUUID(),
          actor.tenantId(),
          execution.id(),
          execution.periodId(),
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
    // 🔴 **实例已批准但链路里没有单独的 APPROVE 动作**时补一条（依据是 identity 侧实例状态）。
    if ("APPROVED".equals(instance.status())
        && localApprovalDecision(actor.tenantId(), instance.id()) == null) {
      sql.execute(
          "insert into finance_period_close_approval(id,tenant_id,execution_id,period_id,"
              + "approval_instance_id,approval_instance_no,action_id,action,node_key,node_name,"
              + "node_order,actor_id,actor_name,comment,decided_at,dwell_seconds,source_system,"
              + "synced_at)"
              + " values(?,?,?,?,?,?,null,'APPROVE','APPROVED','审批通过',0,null,'',"
              + "'identity 侧实例状态为 APPROVED（链路里没有单独的 APPROVE 动作）',"
              + "?::timestamptz,0,'finance',?::timestamptz)",
          UUID.randomUUID(),
          actor.tenantId(),
          execution.id(),
          execution.periodId(),
          instance.id(),
          nz(instance.instanceNo()),
          instance.finishedAt() == null ? now : ApprovalPort.toOffset(instance.finishedAt()),
          now);
    }
  }

  /** 本地镜像里这条审批实例的结论（APPROVED / REJECTED / 未知）。 */
  private String localApprovalDecision(UUID tenantId, UUID instanceId) {
    var row =
        sql.fetchOne(
            "select"
                + " max(case when action = 'APPROVE' then 1 else 0 end) as approved,"
                + " max(case when action = 'REJECT' then 1 else 0 end) as rejected"
                + " from finance_period_close_approval"
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

  // ================================================================ 四类检查项

  /**
   * ① 未对账差异 —— 🔴 **上游未交付 ⇒ 不可判定**。
   *
   * <p>12.3.2.1 对账任务 / 12.3.2.2 差异处理**未开发**（台账状态：未开发）。
   * ⇒ 本项 {@code result = NOT_DETERMINABLE}、{@code classification = CARRYWARD}，
   * **不得**假装通过、**不得**静默当成通过，也**不得**把关账卡死在一个永远满足不了的条件上。
   */
  private ComputedCheck computeUnreconciledDifference() {
    return new ComputedCheck(
        PeriodCloseCheckCode.UNRECONCILED_DIFFERENCE,
        "未对账差异",
        PeriodCloseClassification.CARRYWARD,
        PeriodCloseCheckResult.NOT_DETERMINABLE,
        "上游 12.3.2.1 对账任务 / 12.3.2.2 差异处理未交付（台账状态：未开发）"
            + " ⇒ 本期未对账差异的条数与金额**不可判定**。"
            + "本项按「可结转未清项」处理并显式标注为不可判定（依据即本句），"
            + "既不假装通过，也不把关账卡死在一个永远无法满足的条件上。",
        "12.3.2.1 对账任务 / 12.3.2.2 差异处理（未交付，本模块无数据源）",
        List.of());
  }

  /**
   * ② 未结转成本 —— 数据源：12.4.1.2 的 {@code finance_cost_calculation_run}。
   *
   * <p>规则：
   *
   * <ul>
   *   <li>批次未完成（status ≠ COMPLETED）⇒ **阻断**（成本还没算完）；
   *   <li>批次完成但有缺口（覆盖不全分录 > 0 或缺口数量 > 0）⇒ **阻断**（成本未全额结转）；
   *   <li>全部完成且无缺口 ⇒ 通过；
   *   <li>该期间**一次成本计算都没跑** ⇒ **阻断**（成本未结转）。
   *       若本期确实无存货业务，请用「例外」下调为可结转未清项并写明依据。
   * </ul>
   */
  private ComputedCheck computeUncarriedCost(PeriodWindow window) {
    var rows =
        sql.fetch(
            "select r.id, r.currency, r.status, r.scope_code, r.scope_name, r.valuation_method,"
                + " r.incomplete_entry_count, r.total_shortfall_quantity, r.total_cost_amount,"
                + " r.period_from, r.period_to"
                + " from finance_cost_calculation_run r"
                + " where r.period_from < ?::timestamptz and r.period_to > ?::timestamptz"
                + " order by r.created_at desc, r.id",
            window.endExclusive(),
            window.start());
    var items = new ArrayList<ComputedItem>();
    for (var row : rows) {
      var status = row.get("status", String.class);
      var incomplete = row.get("incomplete_entry_count", Integer.class);
      var shortfall = row.get("total_shortfall_quantity", BigDecimal.class);
      boolean gap = (incomplete != null && incomplete > 0) || (shortfall != null && shortfall.signum() > 0);
      boolean finished = "COMPLETED".equals(status);
      if (finished && !gap) continue;
      var reason =
          finished
              ? "成本计算批次存在缺口（覆盖不全分录 " + incomplete + " 条、缺口数量 " + shortfall
                  + "）：本期成本未全额结转"
              : "成本计算批次未完成（状态 " + status + "）：本期成本尚未结转";
      items.add(
          new ComputedItem(
              "12.4.1.2",
              "COST_RUN",
              row.get("id", UUID.class),
              nz(row.get("scope_code", String.class))
                  + " / "
                  + nz(row.get("valuation_method", String.class))
                  + " "
                  + windowLabel(row.get("period_from", OffsetDateTime.class),
                      row.get("period_to", OffsetDateTime.class)),
              row.get("currency", String.class),
              row.get("total_cost_amount", BigDecimal.class),
              shortfall,
              reason,
              PeriodCloseClassification.BLOCKING,
              null));
    }
    String basis;
    if (rows.isEmpty()) {
      basis =
          "该期间（" + window.startDate() + " ~ " + window.endDate() + "）**没有任何成本计算运行记录**"
              + "（finance_cost_calculation_run 命中 0 条）：本期成本未结转。"
              + "若本期确实无存货业务，请用「例外」下调为可结转未清项并写明依据。";
    } else if (items.isEmpty()) {
      basis =
          "命中 " + rows.size() + " 个成本计算批次，全部已完成且无缺口（覆盖不全分录 0 条、缺口数量 0）："
              + "本期成本已结转。";
    } else {
      basis =
          "命中 " + rows.size() + " 个成本计算批次，其中 " + items.size() + " 个未完成或存在缺口："
              + "本期成本未全额结转 ⇒ 阻断项。";
    }
    return new ComputedCheck(
        PeriodCloseCheckCode.UNCARRIED_COST,
        "未结转成本",
        PeriodCloseClassification.BLOCKING,
        items.isEmpty() && !rows.isEmpty()
            ? PeriodCloseCheckResult.PASS
            : PeriodCloseCheckResult.FAIL,
        basis,
        "finance_cost_calculation_run（12.4.1.2 成本计算执行）",
        items);
  }

  /**
   * ③ 未核销款项 —— 数据源：12.1.1.1 应收 / 12.2.1.1 收款 / 12.3.1.1 结算。
   *
   * <p>规则（对应蓝图加粗句「正常未到期应收应付不要求清零」）：
   *
   * <ul>
   *   <li>应收：争议冻结 / 到期日早于期末 ⇒ **阻断**；未到期 ⇒ **可结转未清项**；
   *   <li>收款：已到账但未核销到应收 ⇒ **可结转未清项**（正常未到期款项）；
   *   <li>结算：预计到账日已过仍未到账 ⇒ **阻断**；尚未到预计日 ⇒ **可结转未清项**。
   * </ul>
   */
  private ComputedCheck computeUnsettledPayment(PeriodWindow window) {
    var items = new ArrayList<ComputedItem>();

    // ---- 12.1.1.1 应收单：本期确认、尚未核销 ----
    // ⚠️ **只取已确认的**（OPEN / PARTIALLY_SETTLED）：
    //   DRAFT 是「还没确认的单」，它不是本期的账；SETTLED / VOID 是终态。
    var receivables =
        sql.fetch(
            "select r.id, r.receivable_no, r.currency, r.amount, r.settled_amount, r.due_date,"
                + " r.status, r.dispute_flag, r.counterparty_name"
                + " from finance_receivable r"
                + " where r.status in ('OPEN','PARTIALLY_SETTLED') and r.settled_amount < r.amount"
                + " and r.confirmed_at >= ?::timestamptz and r.confirmed_at < ?::timestamptz"
                + " order by r.due_date, r.receivable_no",
            window.start(),
            window.endExclusive());
    for (var row : receivables) {
      var amount =
          row.get("amount", BigDecimal.class).subtract(row.get("settled_amount", BigDecimal.class));
      var dueDate = row.get("due_date", LocalDate.class);
      var dispute = Boolean.TRUE.equals(row.get("dispute_flag", Boolean.class));
      boolean overdue = dueDate != null && dueDate.isBefore(window.endDate());
      var classification =
          (dispute || overdue)
              ? PeriodCloseClassification.BLOCKING
              : PeriodCloseClassification.CARRYWARD;
      var reason =
          dispute
              ? "争议冻结中（平台争议未解除）：未核销余额不可带入期末"
              : overdue
                  ? "到期日 " + dueDate + " 早于期末 " + window.endDate() + " 仍未核销：属超期未清"
                  : "未到期（到期日 " + dueDate + "）：按正常未到期应收结转，不要求清零";
      items.add(
          new ComputedItem(
              "12.1.1.1",
              "RECEIVABLE",
              row.get("id", UUID.class),
              row.get("receivable_no", String.class),
              row.get("currency", String.class),
              amount,
              null,
              reason,
              classification,
              dueDate));
    }

    // ---- 12.2.1.1 收款单：已到账但尚未核销 ----
    var receipts =
        sql.fetch(
            "select p.id, p.receipt_no, p.currency, p.unsettled_amount, p.advance_flag, p.payer_name"
                + " from finance_receipt p"
                + " where p.status = 'RECORDED' and p.unsettled_amount > 0"
                + " and p.received_at >= ?::timestamptz and p.received_at < ?::timestamptz"
                + " order by p.receipt_no",
            window.start(),
            window.endExclusive());
    for (var row : receipts) {
      items.add(
          new ComputedItem(
              "12.2.1.1",
              "RECEIPT",
              row.get("id", UUID.class),
              row.get("receipt_no", String.class),
              row.get("currency", String.class),
              row.get("unsettled_amount", BigDecimal.class),
              null,
              "款项已到账但尚未核销到应收单（未核销余额 "
                  + row.get("unsettled_amount", BigDecimal.class)
                  + "）：按正常未到期款项结转",
              PeriodCloseClassification.CARRYWARD,
              null));
    }

    // ---- 12.3.1.1 结算单：结算周期与本期有交集、尚未到账 ----
    var settlements =
        sql.fetch(
            "select s.id, s.settlement_no, s.currency, s.settlement_amount, s.received_amount,"
                + " s.status, s.expected_at, s.shop_name"
                + " from finance_settlement s"
                + " where s.status in ('PENDING','PARTIAL')"
                + " and s.settlement_amount > s.received_amount"
                + " and s.period_start <= ?::date and s.period_end >= ?::date"
                + " order by s.settlement_no",
            window.endDate(),
            window.startDate());
    for (var row : settlements) {
      var expectedAt = row.get("expected_at", OffsetDateTime.class);
      boolean overdue = expectedAt != null && expectedAt.isBefore(window.endExclusive());
      var amount =
          row.get("settlement_amount", BigDecimal.class)
              .subtract(row.get("received_amount", BigDecimal.class));
      var reason =
          overdue
              ? "预计到账日 " + expectedAt + " 已过，结算款仍未到账：属超期未清"
              : "结算款尚未到账（"
                  + (expectedAt == null ? "未登记预计到账日" : "预计到账日 " + expectedAt)
                  + "）：按正常未到期款项结转，不要求清零";
      items.add(
          new ComputedItem(
              "12.3.1.1",
              "SETTLEMENT",
              row.get("id", UUID.class),
              row.get("settlement_no", String.class),
              row.get("currency", String.class),
              amount,
              null,
              reason,
              overdue ? PeriodCloseClassification.BLOCKING : PeriodCloseClassification.CARRYWARD,
              null));
    }

    int blocking = 0;
    for (var item : items) {
      if (item.classification() == PeriodCloseClassification.BLOCKING) blocking++;
    }
    var basis =
        "本期未核销款项共 "
            + items.size()
            + " 条（应收 "
            + receivables.size()
            + " / 收款 "
            + receipts.size()
            + " / 结算 "
            + settlements.size()
            + "），其中超期或争议冻结 "
            + blocking
            + " 条（阻断），其余 "
            + (items.size() - blocking)
            + " 条为正常未到期款项（按蓝图不要求清零，但结转依据须保留）。";
    return new ComputedCheck(
        PeriodCloseCheckCode.UNSETTLED_PAYMENT,
        "未核销款项",
        blocking > 0 ? PeriodCloseClassification.BLOCKING : PeriodCloseClassification.CARRYWARD,
        items.isEmpty() ? PeriodCloseCheckResult.PASS : PeriodCloseCheckResult.FAIL,
        basis,
        "finance_receivable（12.1.1.1）/ finance_receipt（12.2.1.1）/ finance_settlement（12.3.1.1）",
        items);
  }

  /**
   * ④ 未过账凭证 —— 数据源：12.5.2.1 的 {@code finance_voucher}（status = 'DRAFT'）。
   *
   * <p>规则：本期业务日的草稿凭证**一律阻断** —— 关账前必须过账或作废。
   */
  private ComputedCheck computeUnpostedVoucher(PeriodWindow window) {
    var vouchers =
        sql.fetch(
            "select v.id, v.voucher_no, v.currency, v.total_debit, v.voucher_date, v.description"
                + " from finance_voucher v"
                + " where v.status = 'DRAFT' and v.voucher_date >= ?::date and v.voucher_date <= ?::date"
                + " order by v.voucher_date, v.voucher_no",
            window.startDate(),
            window.endDate());
    var items = new ArrayList<ComputedItem>();
    for (var row : vouchers) {
      items.add(
          new ComputedItem(
              "12.5.2.1",
              "VOUCHER",
              row.get("id", UUID.class),
              row.get("voucher_no", String.class),
              row.get("currency", String.class),
              row.get("total_debit", BigDecimal.class),
              null,
              "凭证仍为草稿（未过账，业务日 " + row.get("voucher_date", LocalDate.class)
                  + "）：关账前必须过账或作废",
              PeriodCloseClassification.BLOCKING,
              null));
    }
    var basis =
        "本期业务日范围内共有 "
            + vouchers.size()
            + " 张草稿凭证（finance_voucher.status='DRAFT'）：草稿凭证一律阻断，"
            + "关账前必须过账或作废。";
    return new ComputedCheck(
        PeriodCloseCheckCode.UNPOSTED_VOUCHER,
        "未过账凭证",
        PeriodCloseClassification.BLOCKING,
        vouchers.isEmpty() ? PeriodCloseCheckResult.PASS : PeriodCloseCheckResult.FAIL,
        basis,
        "finance_voucher（12.5.2.1 凭证管理，status='DRAFT'）",
        items);
  }

  // ================================================================ 内部结构

  /** 检查窗口（期间区间在关账里的那一份表述）。 */
  private record PeriodWindow(Record period) {
    LocalDate startDate() {
      return period.get("period_start", LocalDate.class);
    }

    LocalDate endDate() {
      return period.get("period_end", LocalDate.class);
    }

    /** 窗口起点（UTC 零点，闭）。 */
    OffsetDateTime start() {
      return startDate().atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** 窗口终点（UTC 零点，**开**）—— 期间末日整天都在窗口内。 */
    OffsetDateTime endExclusive() {
      return endDate().plusDays(1L).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
  }

  /** 一次检查算出来的结果（落库前）。 */
  private record ComputedCheck(
      PeriodCloseCheckCode code,
      String name,
      PeriodCloseClassification ruleClassification,
      PeriodCloseCheckResult result,
      String basis,
      String dataSource,
      List<ComputedItem> items) {}

  /** 一个未处理事项（落库前）。 */
  private record ComputedItem(
      String sourceModule,
      String sourceType,
      UUID sourceId,
      String sourceNo,
      String currency,
      BigDecimal amount,
      BigDecimal quantity,
      String reason,
      PeriodCloseClassification classification,
      LocalDate dueDate) {}

  /** 一个币种分组。 */
  private record CurrencyAmount(String currency, BigDecimal amount, int itemCount) {}

  /** 按币种分组求和（**只在币种内求和**）。 */
  private static List<CurrencyAmount> groupByCurrency(List<ComputedItem> items) {
    var grouped = new LinkedHashMap<String, List<ComputedItem>>();
    for (var item : items) {
      grouped.computeIfAbsent(item.currency(), k -> new ArrayList<>()).add(item);
    }
    var result = new ArrayList<CurrencyAmount>();
    for (var entry : grouped.entrySet()) {
      var sum = BigDecimal.ZERO;
      for (var item : entry.getValue()) {
        sum = sum.add(item.amount() == null ? BigDecimal.ZERO : item.amount());
      }
      result.add(new CurrencyAmount(entry.getKey(), sum, entry.getValue().size()));
    }
    return result;
  }

  // ================================================================ 取数与工具

  private Record requirePeriod(UUID id) {
    var row = sql.fetchOne("select * from finance_voucher_period where id = ?", id);
    if (row == null) throw notFound("会计期间 " + id + " 不存在或无权查看");
    return row;
  }

  /** 锁住期间行（跑清单 / 关账都要先锁，避免并发双关）。 */
  private Record requirePeriodForUpdate(UUID id) {
    var row = sql.fetchOne("select * from finance_voucher_period where id = ? for update", id);
    if (row == null) throw notFound("会计期间 " + id + " 不存在或无权查看");
    return row;
  }

  private FinancePeriodCloseChecklistRow requireChecklist(UUID id) {
    var row =
        sql.fetchOne(
            "select c.*, p.period_code from finance_period_close_checklist c"
                + " join finance_voucher_period p"
                + "   on p.tenant_id = c.tenant_id and p.id = c.period_id"
                + " where c.id = ?",
            id);
    if (row == null) throw notFound("关账检查清单 " + id + " 不存在或无权查看");
    return checklistRow(row);
  }

  private FinancePeriodCloseCheckRow requireCheck(UUID id) {
    var row = sql.fetchOne("select * from finance_period_close_check where id = ?", id);
    if (row == null) throw notFound("检查项 " + id + " 不存在或无权查看");
    return checkRow(row);
  }

  private FinancePeriodCloseExecutionRow requireExecution(UUID id) {
    var row =
        sql.fetchOne(
            "select e.*, p.period_code, c.checklist_no from finance_period_close_execution e"
                + " join finance_voucher_period p"
                + "   on p.tenant_id = e.tenant_id and p.id = e.period_id"
                + " join finance_period_close_checklist c"
                + "   on c.tenant_id = e.tenant_id and c.id = e.checklist_id"
                + " where e.id = ?",
            id);
    if (row == null) throw notFound("关账执行记录 " + id + " 不存在或无权查看");
    return executionRow(row);
  }

  private List<FinancePeriodCloseCheckRow> checks(UUID checklistId) {
    return checks(checklistId, amounts(checklistId));
  }

  /** 检查项列表（带上每一项**按币种分组**的金额）。 */
  private List<FinancePeriodCloseCheckRow> checks(
      UUID checklistId, List<FinancePeriodCloseCheckAmountRow> amounts) {
    var byCheck = new LinkedHashMap<UUID, List<FinancePeriodCloseCheckAmountRow>>();
    for (var amount : amounts) {
      byCheck.computeIfAbsent(amount.checkId(), k -> new ArrayList<>()).add(amount);
    }
    return sql.fetch(
            "select * from finance_period_close_check where checklist_id = ? order by check_code",
            checklistId)
        .map(r -> checkRow(r, byCheck.getOrDefault(r.get("id", UUID.class), List.of())));
  }

  private List<FinancePeriodCloseCheckAmountRow> amounts(UUID checklistId) {
    return sql.fetch(
            "select * from finance_period_close_check_amount where checklist_id = ?"
                + " order by check_id, currency",
            checklistId)
        .map(this::amountRow);
  }

  private List<FinancePeriodCloseApprovalRow> approvals(UUID executionId) {
    return sql.fetch(
            "select * from finance_period_close_approval where execution_id = ?"
                + " order by decided_at, id",
            executionId)
        .map(this::approvalRow);
  }

  /**
   * 整份清单**按币种**汇总（跨检查项，但**只在同一个币种内**相加）。
   *
   * <p>🔴 结构上不存在「跨币种求和」这一格：这张表与这个方法都只按 currency 分组。
   */
  private static List<CurrencyAmount> checklistCurrencyTotals(
      List<FinancePeriodCloseCheckAmountRow> amounts) {
    var grouped = new LinkedHashMap<String, BigDecimal>();
    var counts = new LinkedHashMap<String, Integer>();
    for (var amount : amounts) {
      grouped.merge(amount.currency(), amount.amount(), BigDecimal::add);
      counts.merge(amount.currency(), amount.itemCount(), Integer::sum);
    }
    var result = new ArrayList<CurrencyAmount>();
    for (var entry : grouped.entrySet()) {
      result.add(new CurrencyAmount(entry.getKey(), entry.getValue(), counts.get(entry.getKey())));
    }
    return result;
  }

  /**
   * 按币种分组 + 可选的本位币折算。
   *
   * <p>🔴 **汇率不可得就只给分组、不折算** —— 绝不拿一个猜的汇率折出一个看起来像真的总数。
   * 也**刻意不产出跨币种的「合计」**：多币种绝不相加。
   */
  private List<FinancePeriodCloseCurrencyGroup> currencyGroups(
      List<CurrencyAmount> totals, String baseCurrency, LocalDate asOf, String bearer) {
    var groups = new ArrayList<FinancePeriodCloseCurrencyGroup>();
    for (var total : totals) {
      BigDecimal baseAmount = null;
      BigDecimal rate = null;
      LocalDate rateDate = null;
      String note;
      if (total.currency().equals(baseCurrency)) {
        baseAmount = total.amount();
        rate = BigDecimal.ONE;
        note = "本币种，无需折算";
      } else {
        try {
          var snapshot = exchangeRates.latest(total.currency(), baseCurrency, asOf, bearer);
          rate = snapshot.rate();
          rateDate = snapshot.effectiveDate();
          baseAmount = total.amount().multiply(rate).setScale(4, RoundingMode.HALF_UP);
          note =
              snapshot.display()
                  + (snapshot.inverted() ? "（该汇率为反向推导得出）" : "")
                  + "；本位币金额按此汇率折算，仅供参考，**不与其它币种相加**";
        } catch (ResponseStatusException e) {
          note =
              "汇率不可得，未折算："
                  + (e.getReason() == null ? String.valueOf(e.getMessage()) : e.getReason());
        } catch (RuntimeException e) {
          note = "汇率不可得，未折算：" + e.getMessage();
        }
      }
      groups.add(
          new FinancePeriodCloseCurrencyGroup(
              total.currency(),
              total.amount(),
              total.itemCount(),
              baseAmount,
              rate,
              rateDate,
              note));
    }
    return groups;
  }

  private FinancePeriodCloseChecklistRow checklistRow(Record r) {
    return new FinancePeriodCloseChecklistRow(
        r.get("id", UUID.class),
        r.get("period_id", UUID.class),
        r.get("period_code", String.class),
        r.get("run_no", Integer.class),
        r.get("checklist_no", String.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("base_currency", String.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class),
        r.get("created_by", UUID.class),
        nz(r.get("note", String.class)),
        r.get("created_at", OffsetDateTime.class));
  }

  private FinancePeriodCloseCheckRow checkRow(Record r) {
    return checkRow(r, List.of());
  }

  private FinancePeriodCloseCheckRow checkRow(
      Record r, List<FinancePeriodCloseCheckAmountRow> amounts) {
    var code = PeriodCloseCheckCode.valueOf(r.get("check_code", String.class));
    var rule = PeriodCloseClassification.valueOf(r.get("rule_classification", String.class));
    var classification = PeriodCloseClassification.valueOf(r.get("classification", String.class));
    var result = PeriodCloseCheckResult.valueOf(r.get("result", String.class));
    return new FinancePeriodCloseCheckRow(
        r.get("id", UUID.class),
        r.get("checklist_id", UUID.class),
        r.get("period_id", UUID.class),
        code,
        code.label(),
        r.get("check_name", String.class),
        rule,
        rule.label(),
        classification,
        classification.label(),
        result,
        result.label(),
        r.get("classification_basis", String.class),
        nz(r.get("exception_basis", String.class)),
        r.get("exception_by", UUID.class),
        r.get("exception_at", OffsetDateTime.class),
        r.get("item_count", Integer.class),
        r.get("data_source", String.class),
        List.copyOf(amounts),
        r.get("created_at", OffsetDateTime.class));
  }

  private FinancePeriodCloseCheckItemRow itemRow(Record r) {
    var classification =
        PeriodCloseClassification.valueOf(r.get("classification", String.class));
    return new FinancePeriodCloseCheckItemRow(
        r.get("id", UUID.class),
        r.get("checklist_id", UUID.class),
        r.get("check_id", UUID.class),
        r.get("period_id", UUID.class),
        r.get("source_module", String.class),
        r.get("source_type", String.class),
        r.get("source_id", UUID.class),
        r.get("source_no", String.class),
        r.get("currency", String.class),
        r.get("amount", BigDecimal.class),
        r.get("quantity", BigDecimal.class),
        r.get("reason", String.class),
        classification,
        classification.label(),
        r.get("due_date", LocalDate.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private FinancePeriodCloseCheckAmountRow amountRow(Record r) {
    return new FinancePeriodCloseCheckAmountRow(
        r.get("id", UUID.class),
        r.get("checklist_id", UUID.class),
        r.get("check_id", UUID.class),
        r.get("currency", String.class),
        r.get("amount", BigDecimal.class),
        r.get("item_count", Integer.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private FinancePeriodCloseExecutionRow executionRow(Record r) {
    var status = PeriodCloseExecutionStatus.valueOf(r.get("status", String.class));
    return new FinancePeriodCloseExecutionRow(
        r.get("id", UUID.class),
        r.get("period_id", UUID.class),
        r.get("period_code", String.class),
        r.get("checklist_id", UUID.class),
        r.get("checklist_no", String.class),
        r.get("close_no", String.class),
        status,
        status.label(),
        r.get("blocking_count", Integer.class),
        r.get("carryforward_count", Integer.class),
        r.get("not_determinable_count", Integer.class),
        nz(r.get("carryforward_basis", String.class)),
        nz(r.get("reason", String.class)),
        r.get("approval_instance_id", UUID.class),
        nz(r.get("approval_instance_no", String.class)),
        nz(r.get("approval_status", String.class)),
        Boolean.TRUE.equals(r.get("period_locked", Boolean.class)),
        nz(r.get("lock_note", String.class)),
        r.get("closed_at", OffsetDateTime.class),
        r.get("closed_by", UUID.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinancePeriodCloseApprovalRow approvalRow(Record r) {
    return new FinancePeriodCloseApprovalRow(
        r.get("id", UUID.class),
        r.get("execution_id", UUID.class),
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

  /** 审批动作归一（上游只给三种，这里把空值归到 SUBMIT）。 */
  private static String normalizeAction(String raw) {
    if (raw == null || raw.isBlank()) return "SUBMIT";
    var value = raw.trim().toUpperCase(Locale.ROOT);
    return switch (value) {
      case "APPROVE", "REJECT", "TRANSFER" -> value;
      default -> "SUBMIT";
    };
  }

  /** 成本批次的窗口标签（给人看的一段）。 */
  private static String windowLabel(OffsetDateTime from, OffsetDateTime to) {
    if (from == null || to == null) return "";
    return from.atZoneSameInstant(ZoneOffset.UTC).toLocalDate()
        + "~"
        + to.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
  }

  private static String periodStatusLabel(String status) {
    return "CLOSED".equals(status) ? "关闭" : "开放";
  }

  private static String requireText(String raw, String label) {
    var value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) throw badRequest(label + "不能为空");
    if (value.length() > 1000) throw badRequest(label + "长度不能超过 1000 个字符");
    return value;
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  /** 拼绑定值（返回 {@code Object[]} —— jOOQ 的 {@code fetch(String, Object...)} 收它）。 */
  private static Object[] concat(List<Object> binds, Object... extra) {
    var all = new ArrayList<>(binds);
    all.addAll(List.of(extra));
    return all.toArray();
  }
}
