package com.starcon.finance;

import static com.starcon.finance.ReconciliationDifferenceModels.*;

import com.starcon.finance.ReconciliationTaskModels.FinanceReconDifferenceType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.3.2.2 差异处理。
 *
 * <p>蓝图 12-finance.md：「处理对账差异：分类（我方多记/对方多记/时间差/汇率差/手续费遗漏）、
 * 指定责任人、人工判定与调整、闭环跟踪。」输入：「差异明细；处理结论；调整凭证或说明。」
 * 输出：「差异处理记录 + 调整分录 + 闭环状态。」关系：「上游 = 12.3.2.1；下游 = 12.5.2 凭证、
 * 1.3 预警。<b>差异分类是改进闭环的输入</b>：某类差异反复出现说明上游流程有系统性问题。」
 *
 * <p><b>与上游 12.3.2.1 的关系</b>：上游的
 * {@code finance_reconciliation_difference} 是<b>执行时的事实</b> ——
 * 它的触发器 {@code guard_finance_reconciliation_difference_immutable}
 * 只放行 {@code status} <b>向前</b>推进，其余每个字段（差异类型 / 侧 / 两侧金额 /
 * 币种 / 差额 / 归因 / 原因 …）<b>冻结</b>。所以：
 *
 * <ul>
 *   <li>分类、责任人、结论、调整分录全部落在**本模块自己的三张表**里
 *       （{@code finance_reconciliation_handling} /
 *       {@code finance_reconciliation_adjustment} /
 *       {@code finance_reconciliation_handling_event}）；</li>
 *   <li>上游那张表**只被推进 {@code status}** —— **零 ALTER、零 DROP、
 *       零 ADD COLUMN**；</li>
 *   <li>两边的状态<b>同词表、同相位</b>，由 DB 触发器逐次核对 ⇒ 直连 SQL 也写不出
 *       「处理记录说已核销、上游差异还挂在待处理」。</li>
 * </ul>
 *
 * <p><b>五条口径纪律</b>（每一条都有实现，不是口号）：
 *
 * <ol>
 *   <li><b>差异是事实，处理记录另起一张表。</b>不 ALTER 上游、不改写上游的事实字段。</li>
 *   <li><b>闭环必须有责任人、必须有分类、必须有结论。</b>表级 CHECK 钉死。</li>
 *   <li><b>调整凭证或说明，至少有一个。</b>两者都没有 = 这条分录什么都不是。</li>
 *   <li><b>跨币种绝不相加。</b>每条分录自带币种，汇总按币种分组；上游跨币种差异的
 *       差额恒为 {@code null}（汇率不可得，<b>不按 1:1 兜底</b>）。</li>
 *   <li><b>差异分类是改进闭环的输入。</b>队列汇总里给出<b>分类 × 币种</b>的分布
 *       —— 某一类反复出现，就是上游流程有系统性问题的证据。</li>
 * </ol>
 *
 * <p><b>本模块不生成凭证</b>：蓝图说下游 = 12.5.2 凭证，而 12.5.2.1 未交付 ⇒
 * 本模块只<b>登记</b>调整分录（凭证号或说明），凭证本身由 12.5.2.1 生成
 * （自己造一套凭证 = 造出第二套账）。
 */
@Service
public class ReconciliationDifferenceService {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ReconciliationDifferenceService.class);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 分类词表。 */
  static final List<String> CATEGORIES =
      List.of(
          "BOOK_OVER_RECORDED",
          "COUNTERPARTY_OVER_RECORDED",
          "TIMING_DIFFERENCE",
          "FX_DIFFERENCE",
          "FEE_OMISSION");

  /** 状态词表（与上游 {@code finance_reconciliation_difference.status} 同词表）。 */
  static final List<String> STATUSES = List.of("OPEN", "CLAIMED", "ADJUSTED", "WRITTEN_OFF");

  /**
   * 队列一行的**列清单**（列表与单条详情共用 ⇒ 两边不会漂移）。
   *
   * <p>🔴 {@code adjustment_count} 是**条数**而不是金额 —— 一条差异的调整分录**可能跨币种**，
   * 行上相加会算错 ⇒ 金额一律落到**按币种分组**的汇总里。
   */
  static final String DIFF_COLUMNS =
      "d.id as difference_id, d.task_id, d.difference_type, d.side, d.book_source,"
          + " d.group_key, d.external_kind, d.external_line_id, d.book_line_id,"
          + " d.occurred_on, d.reference, d.transaction_type, d.external_amount,"
          + " d.book_amount, d.currency, d.direction, d.difference_amount,"
          + " d.exchange_rate_available, d.attribution, d.reason, d.status,"
          + " h.id as handling_id, h.category, h.category_reason, h.assignee_id,"
          + " h.claimed_at, h.conclusion, h.closed_at,"
          + " (select count(*) from finance_reconciliation_adjustment a"
          + "   where a.tenant_id = d.tenant_id and a.handling_id = h.id) as adjustment_count";

  /** 队列一行的**来源**（差异 ⇄ 处理记录，左连接 ⇒ 没处理过的差异也在队列里）。 */
  static final String DIFF_FROM =
      " from finance_reconciliation_difference d"
          + " left join finance_reconciliation_handling h"
          + "   on h.tenant_id = d.tenant_id and h.difference_id = d.id";

  private final DSLContext sql;

  public ReconciliationDifferenceService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 必须把「本模块没接」与「上游 / 下游没交付」分开写。
   */
  public ReconciliationDiffCapabilities capabilities() {
    var capabilities = new ArrayList<ReconciliationDiffCapability>();
    var gaps = new ArrayList<ReconciliationDiffGap>();

    capabilities.add(
        new ReconciliationDiffCapability(
            "12.3.2.2",
            "差异处理（分类 / 责任人 / 判定 / 闭环）",
            true,
            "finance_reconciliation_handling + finance_reconciliation_adjustment"
                + " + finance_reconciliation_handling_event",
            "",
            "🔴 上游 `finance_reconciliation_difference` 的差异是**执行时的事实**"
                + "（触发器只放行 `status` 向前推进，其余字段冻结）⇒ "
                + "分类、责任人、结论、调整分录全部落在**本模块自己的三张表**，"
                + "上游**只被推进 status**（零 ALTER、零 DROP、零 ADD COLUMN）。"
                + "处理状态与上游差异状态**同词表、同相位**（OPEN / CLAIMED / ADJUSTED / "
                + "WRITTEN_OFF），DB 触发器逐次核对 ⇒ 直连 SQL 也写不出"
                + "「处理记录说已核销、上游差异还挂在待处理」。"));

    capabilities.add(
        new ReconciliationDiffCapability(
            "12.3.2.2",
            "分类归因（人工分类 + 理由）",
            true,
            "finance_reconciliation_handling.category / category_reason",
            "",
            "五类归因：我方多记 / 对方多记 / 时间差 / 汇率差 / 手续费遗漏。"
                + "🔴 这是**归因**、不是上游的差异**类型**（上游说的是「差在哪儿」，"
                + "这里说的是「**为什么**差」）—— 后者才是改进闭环的输入。"
                + "**理由必填**（表级 CHECK）；没分类就没法闭环。"));

    capabilities.add(
        new ReconciliationDiffCapability(
            "12.3.2.2",
            "指定责任人 + 认领",
            true,
            "finance_reconciliation_handling.assignee_id",
            "",
            "「认领」= 接手这条差异并指定责任人（不传 assigneeId 就是认领人自己）；"
                + "「改派」= 在已认领态换责任人。🔴 本仓**没有用户目录服务** ⇒ "
                + "**只存用户 uuid**、不存前端编出来的姓名（与 08 仓储的 "
                + "`assignee_user_id` 同一口径）。**没有责任人的差异进不了闭环**"
                + "（表级 CHECK）。"));

    capabilities.add(
        new ReconciliationDiffCapability(
            "12.3.2.2",
            "调整分录（凭证或说明）",
            true,
            "finance_reconciliation_adjustment（**append-only**）",
            "",
            "蓝图输入句是「调整凭证**或**说明」⇒ 表级 CHECK 要求二者至少有一个，"
                + "两者都没有 = 这条分录什么都不是。分录**一次性事实**"
                + "（运行角色只给 SELECT + INSERT + 触发器双保险）。"
                + "🔴 每条分录**自带币种** ⇒ 汇总**按币种分组**，"
                + "结构上不存在「把 USD 和 EUR 加起来」这一格。"));

    capabilities.add(
        new ReconciliationDiffCapability(
            "12.3.2.2",
            "闭环跟踪（状态 + 时间线）",
            true,
            "finance_reconciliation_handling_event（**append-only**）",
            "",
            "闭环状态机：OPEN → CLAIMED → ADJUSTED / WRITTEN_OFF，**只能向前**；"
                + "已调整 / 已核销是**终态**（DB 触发器冻结整行，连分类都不能再改）。"
                + "🔴 上游 `finance_reconciliation_event` 的 `event_type` 词表被 CHECK "
                + "钉死成任务级事件，塞不下「差异认领」，而**放行新类型要 ALTER 上游的表**"
                + "⇒ 本模块**自建**处理级时间线（事件类型是本模块自己的词表）。"
                + "判定「已调整」**至少要有一条调整分录**（DB 触发器钉死）。"));

    capabilities.add(
        new ReconciliationDiffCapability(
            "12.3.2.2",
            "分类分布（改进闭环的输入）",
            true,
            "GET /finance/reconciliation-differences（汇总段）",
            "",
            "蓝图原话：「差异分类是改进闭环的输入：某类差异反复出现说明上游流程有"
                + "系统性问题。」⇒ 队列汇总给出**分类 × 币种**的分布（条数 + 同币种差额"
                + "+ 同币种调整额），页面据此回答「哪一类在反复出现」。"));

    capabilities.add(
        new ReconciliationDiffCapability(
            "12.3.2.1",
            "对账任务（上游差异清单）",
            true,
            "finance_reconciliation_difference（同服务同库 ⇒ 直接读）",
            "",
            "本模块读上游差异清单（RLS 已隔离），**只推进 status**，不改任何事实字段。"
                + "上游差异的差额 / 汇率可得性**原样透传**：跨币种时差额恒为 null"
                + "（汇率不可得，**不按 1:1 兜底**）。"));

    gaps.add(
        new ReconciliationDiffGap(
            "12.5.2.1", "凭证", "finance", false, "12 财务核算",
            "下游未交付：本模块**只登记**调整分录（凭证号或说明），"
                + "**不生成凭证** —— 自己造一套凭证就是造出第二套账。"
                + "凭证由 12.5.2.1 生成后回填 voucherNo。"));

    gaps.add(
        new ReconciliationDiffGap(
            "1.3.2.1", "预警规则", "portal", false, "01 工作台与门户",
            "下游未交付：本模块**不推送**预警，也不写预警规则 —— "
                + "「某类差异反复出现」由分类分布暴露给 1.3 预警去取数。"));

    gaps.add(
        new ReconciliationDiffGap(
            "12.3.2.3", "自动匹配规则", "finance", false, "12 结算与对账",
            "上游未交付：分类分布（本模块的输出）本该喂给规则调优，"
                + "但 12.3.2.3 还没做 ⇒ 分布只在本模块的页面上呈现。"));

    gaps.add(
        new ReconciliationDiffGap(
            "identity", "用户目录", "identity", false, "17 组织权限与设置",
            "上游未接线：本仓没有用户目录服务可查 ⇒ 处理记录**只存责任人 uuid**，"
                + "**不存姓名**（前端编的姓名会变成假数据）。"));

    return new ReconciliationDiffCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：词表 + **真实数据**（任务 / 币种 / 责任人都来自真实表）。 */
  public ReconciliationDiffOptions options() {
    var statuses = new ArrayList<ReconciliationDiffOption>();
    for (var status : ReconciliationDiffStatus.values()) {
      statuses.add(new ReconciliationDiffOption(status.name(), status.label()));
    }

    var categories = new ArrayList<ReconciliationDiffOption>();
    for (var category : ReconciliationDiffCategory.values()) {
      categories.add(new ReconciliationDiffOption(category.name(), category.label()));
    }

    var differenceTypes = new ArrayList<ReconciliationDiffOption>();
    for (var type : FinanceReconDifferenceType.values()) {
      differenceTypes.add(new ReconciliationDiffOption(type.name(), type.label()));
    }

    var directions = new ArrayList<ReconciliationDiffOption>();
    for (var direction : ReconciliationDiffDirection.values()) {
      directions.add(new ReconciliationDiffOption(direction.name(), direction.label()));
    }

    var tasks =
        sql.fetch(
                "select t.id,t.task_no,t.account_no,t.currency,t.period_start,t.period_end"
                    + " from finance_reconciliation_task t"
                    + " where exists (select 1 from finance_reconciliation_difference d"
                    + "                 where d.tenant_id = t.tenant_id and d.task_id = t.id)"
                    + " order by t.period_end desc, t.task_no desc")
            .map(
                r ->
                    new ReconciliationDiffTask(
                        r.get("id", UUID.class),
                        r.get("task_no", String.class),
                        r.get("account_no", String.class),
                        r.get("currency", String.class),
                        r.get("period_start", LocalDate.class).toString(),
                        r.get("period_end", LocalDate.class).toString()));

    var currencies =
        sql.fetch("select distinct currency from finance_reconciliation_difference order by currency")
            .map(r -> r.get(0, String.class));

    var assignees =
        sql.fetch(
                "select distinct assignee_id from finance_reconciliation_handling"
                    + " where assignee_id is not null order by assignee_id")
            .map(r -> r.get(0, UUID.class));

    return new ReconciliationDiffOptions(
        List.copyOf(statuses),
        List.copyOf(categories),
        List.copyOf(differenceTypes),
        List.copyOf(directions),
        List.copyOf(tasks),
        List.copyOf(currencies),
        List.copyOf(assignees),
        "任务候选取自**真实存在且已产出差异**的对账任务；币种取自真实差异；"
            + "责任人取自真实处理记录（**只存 uuid** —— 本仓没有用户目录服务，"
            + "存姓名只能是前端编的）。状态 / 分类 / 差异类型 / 方向都是**受控词表**。");
  }

  // ================================================================ 队列

  /**
   * 差异处理队列（真分页 + 真筛选 + 真搜索 + 真汇总）。
   *
   * <p>🔴 汇总**按币种分组**，**绝不跨币种合计**。
   */
  public ReconciliationDiffQueuePage queue(
      UUID taskId,
      String status,
      String category,
      String currency,
      UUID assigneeId,
      String differenceType,
      String keyword,
      int page,
      int size) {
    var pageNo = Math.max(0, page);
    var pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    var filter = buildFilter(taskId, status, category, currency, assigneeId, differenceType, keyword);

    var total =
        sql.fetchOne(
                "select count(*) from finance_reconciliation_difference d"
                    + " left join finance_reconciliation_handling h"
                    + "   on h.tenant_id = d.tenant_id and h.difference_id = d.id"
                    + filter.where(),
                filter.args().toArray())
            .get(0, Long.class);

    var bindings = new ArrayList<Object>(filter.args());
    bindings.add(pageSize);
    bindings.add(pageNo * pageSize);
    var rows =
        sql.fetch(
            "select " + DIFF_COLUMNS + DIFF_FROM + filter.where()
                + " order by d.currency, d.difference_type, d.id limit ? offset ?",
            bindings.toArray());

    return new ReconciliationDiffQueuePage(
        rows.map(this::diffRow),
        pageNo,
        pageSize,
        total,
        currencySummary(filter),
        categoryStats(filter),
        assigneeStats(filter),
        "🔴 汇总**按币种分组**：上游差额与调整金额只在**同一币种内**相加，"
            + "**绝不**跨币种合计。跨币种差异的差额恒为 null"
            + "（汇率不可得，**不按 1:1 兜底** —— 写 0 会被读成「账是平的」）。"
            + "「分类分布」是**改进闭环的输入**：某一类反复出现 ⇒ 上游流程有系统性问题。");
  }

  /** 一个币种的汇总（**同币种内**才相加）。 */
  private List<ReconciliationDiffCurrencySummary> currencySummary(Filter filter) {
    var byCurrency = new LinkedHashMap<String, Record>();
    var rows =
        sql.fetch(
            "select d.currency,"
                + " count(*) as total_count,"
                + " count(*) filter (where d.status = 'OPEN') as open_count,"
                + " count(*) filter (where d.status = 'CLAIMED') as claimed_count,"
                + " count(*) filter (where d.status = 'ADJUSTED') as adjusted_count,"
                + " count(*) filter (where d.status = 'WRITTEN_OFF') as written_off_count,"
                + " sum(case when d.exchange_rate_available then d.difference_amount end)"
                + "   as difference_amount,"
                + " count(*) filter (where d.exchange_rate_available = false) as rate_missing"
                + " from finance_reconciliation_difference d"
                + " left join finance_reconciliation_handling h"
                + "   on h.tenant_id = d.tenant_id and h.difference_id = d.id"
                + filter.where()
                + " group by d.currency order by d.currency",
            filter.args().toArray());
    for (var row : rows) {
      byCurrency.put(row.get("currency", String.class), row);
    }

    // 调整分录按币种分组（**每条分录自带币种** ⇒ 这里绝不相加不同币种）。
    var adjustments = new LinkedHashMap<String, Record>();
    var adjustmentRows =
        sql.fetch(
            "select a.currency, count(*) as entry_count, sum(a.adjustment_amount) as adjusted_amount"
                + " from finance_reconciliation_adjustment a"
                + " join finance_reconciliation_handling h"
                + "   on h.tenant_id = a.tenant_id and h.id = a.handling_id"
                + " join finance_reconciliation_difference d"
                + "   on d.tenant_id = h.tenant_id and d.id = h.difference_id"
                + filter.where()
                + " group by a.currency order by a.currency",
            filter.args().toArray());
    for (var row : adjustmentRows) {
      adjustments.put(row.get("currency", String.class), row);
    }

    var summaries = new ArrayList<ReconciliationDiffCurrencySummary>();
    for (var entry : byCurrency.entrySet()) {
      var currency = entry.getKey();
      var row = entry.getValue();
      var adjustment = adjustments.get(currency);
      summaries.add(
          new ReconciliationDiffCurrencySummary(
              currency,
              row.get("open_count", Long.class),
              row.get("claimed_count", Long.class),
              row.get("adjusted_count", Long.class),
              row.get("written_off_count", Long.class),
              row.get("total_count", Long.class),
              row.get("difference_amount", BigDecimal.class),
              adjustment == null ? null : adjustment.get("adjusted_amount", BigDecimal.class),
              adjustment == null ? 0L : adjustment.get("entry_count", Long.class),
              row.get("rate_missing", Long.class) == 0L,
              "该币种的金额**只在同币种内相加**；"
                  + "跨币种差异的差额恒为 null（汇率不可得，不按 1:1 兜底）"));
    }
    return List.copyOf(summaries);
  }

  /** 分类分布（**改进闭环的输入**）：分类 × 币种。 */
  private List<ReconciliationDiffCategoryStat> categoryStats(Filter filter) {
    var counts = new LinkedHashMap<String, Record>();
    var rows =
        sql.fetch(
            "select h.category, d.currency, count(*) as n,"
                + " sum(case when d.exchange_rate_available then d.difference_amount end)"
                + "   as difference_amount"
                + " from finance_reconciliation_difference d"
                + " join finance_reconciliation_handling h"
                + "   on h.tenant_id = d.tenant_id and h.difference_id = d.id"
                + filter.where()
                + " and h.category is not null"
                + " group by h.category, d.currency order by h.category, d.currency",
            filter.args().toArray());
    for (var row : rows) {
      counts.put(row.get("category", String.class) + "|" + row.get("currency", String.class), row);
    }

    var adjusted = new LinkedHashMap<String, BigDecimal>();
    var adjustedRows =
        sql.fetch(
            "select h.category, a.currency, sum(a.adjustment_amount) as adjusted_amount"
                + " from finance_reconciliation_adjustment a"
                + " join finance_reconciliation_handling h"
                + "   on h.tenant_id = a.tenant_id and h.id = a.handling_id"
                + " join finance_reconciliation_difference d"
                + "   on d.tenant_id = h.tenant_id and d.id = h.difference_id"
                + filter.where()
                + " group by h.category, a.currency",
            filter.args().toArray());
    for (var row : adjustedRows) {
      adjusted.put(
          row.get("category", String.class) + "|" + row.get("currency", String.class),
          row.get("adjusted_amount", BigDecimal.class));
    }

    var stats = new ArrayList<ReconciliationDiffCategoryStat>();
    for (var entry : counts.entrySet()) {
      var row = entry.getValue();
      var category = row.get("category", String.class);
      var currency = row.get("currency", String.class);
      stats.add(
          new ReconciliationDiffCategoryStat(
              category,
              categoryLabel(category),
              currency,
              row.get("n", Long.class),
              row.get("difference_amount", BigDecimal.class),
              adjusted.get(category + "|" + currency)));
    }
    return List.copyOf(stats);
  }

  /** 责任人工作量（**只计数不记金额** ⇒ 不涉及币种相加）。 */
  private List<ReconciliationDiffAssigneeStat> assigneeStats(Filter filter) {
    var rows =
        sql.fetch(
            "select h.assignee_id,"
                + " count(*) filter (where d.status = 'CLAIMED') as claimed_count,"
                + " count(*) filter (where d.status = 'ADJUSTED') as adjusted_count,"
                + " count(*) filter (where d.status = 'WRITTEN_OFF') as written_off_count"
                + " from finance_reconciliation_difference d"
                + " join finance_reconciliation_handling h"
                + "   on h.tenant_id = d.tenant_id and h.difference_id = d.id"
                + filter.where()
                + " and h.assignee_id is not null"
                + " group by h.assignee_id order by h.assignee_id",
            filter.args().toArray());
    return rows.map(
        r ->
            new ReconciliationDiffAssigneeStat(
                r.get("assignee_id", UUID.class),
                r.get("claimed_count", Long.class),
                r.get("adjusted_count", Long.class),
                r.get("written_off_count", Long.class)));
  }

  // ================================================================ 详情

  /** 一条差异的处理详情（差异 + 调整分录 + 时间线 + 可执行动作）。 */
  public ReconciliationDiffDetail detail(UUID differenceId) {
    var difference = diffRecord(differenceId);
    var handling = handlingOf(differenceId);
    var adjustments = adjustmentsOf(differenceId);
    var events = eventRows(differenceId, 50);
    return new ReconciliationDiffDetail(
        diffRow(difference),
        handling == null ? null : handling.get("claimed_by", UUID.class),
        handling == null ? null : handling.get("category_set_by", UUID.class),
        handling == null ? null : handling.get("category_set_at", OffsetDateTime.class),
        adjustments,
        events,
        actionsOf(difference, handling, adjustments),
        "🔴 差异本身是**执行时的事实**（上游字段冻结），本模块只**推进它的 status**；"
            + "分类、责任人、结论、调整分录都落在本模块自己的表里。"
            + "「已调整」**至少要有一条调整分录**；「已核销」= 判定不需要调整。");
  }

  /** 处理时间线（真分页；append-only 举证）。 */
  public ReconciliationDiffEventPage events(UUID differenceId, int page, int size) {
    requireDifference(differenceId);
    var pageNo = Math.max(0, page);
    var pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    var total =
        sql.fetchOne(
                "select count(*) from finance_reconciliation_handling_event where difference_id = ?",
                differenceId)
            .get(0, Long.class);

    var rows =
        sql.fetch(
            "select id,handling_id,difference_id,event_type,from_status,to_status,summary,"
                + " actor_id,occurred_at from finance_reconciliation_handling_event"
                + " where difference_id = ?"
                + " order by occurred_at desc, id desc limit ? offset ?",
            differenceId,
            pageSize,
            pageNo * pageSize);

    return new ReconciliationDiffEventPage(rows.map(this::eventRow), pageNo, pageSize, total);
  }

  // ================================================================ 动作

  /**
   * 认领这条差异（并指定责任人）。
   *
   * <p>⚠️ 不传 {@code assigneeId} = 认领人就是**操作者自己**（取自已认证会话，
   * **不信任任何客户端租户 / 用户头**）。
   */
  @Transactional
  public ReconciliationDiffHandling claim(
      UUID differenceId, ReconciliationDiffClaimInput input, AccessService.Access actor) {
    var difference = requireDifference(differenceId);
    var status = difference.get("status", String.class);
    if (!"OPEN".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该差异已被处理（当前状态 " + statusLabel(status) + "），不能再认领");
    }

    var assigneeId = input.assigneeId() == null ? actor.userId() : input.assigneeId();
    var now = nowUtc();

    // 🔴 **先推上游、再写处理记录**：处理记录的触发器要求两边状态**同相**。
    advanceUpstream(difference, "CLAIMED", actor.tenantId());

    var handling = handlingOf(differenceId);
    if (handling == null) {
      insertHandling(difference, actor.tenantId(), "CLAIMED", assigneeId, actor.userId(), now);
    } else {
      updateHandling(
          handling,
          "status = 'CLAIMED', assignee_id = ?, claimed_by = ?, claimed_at = ?::timestamptz,"
              + " version = version + 1, updated_at = ?::timestamptz",
          assigneeId,
          actor.userId(),
          now,
          now);
    }

    appendEvent(
        differenceId,
        "ASSIGNED",
        "OPEN",
        "CLAIMED",
        "认领差异并指定责任人 " + assigneeId
            + (input.note() == null || input.note().isBlank() ? "" : "：" + input.note().trim()),
        Map.of("assigneeId", assigneeId.toString(), "note", trim(input.note())),
        actor.userId(),
        now);

    return handlingRow(requireHandling(differenceId));
  }

  /**
   * 改派责任人（**只限已认领、且未闭环**）。
   *
   * <p>⚠️ 已调整 / 已核销是终态 ⇒ DB 触发器冻结整行，改派会被拒。
   */
  @Transactional
  public ReconciliationDiffHandling assign(
      UUID differenceId, ReconciliationDiffAssignInput input, AccessService.Access actor) {
    var difference = requireDifference(differenceId);
    var status = difference.get("status", String.class);
    if (!"CLAIMED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有在「已认领」的差异上才能改派责任人（当前状态 " + statusLabel(status) + "）");
    }
    var handling = requireHandling(differenceId);
    var now = nowUtc();
    var previous = handling.get("assignee_id", UUID.class);

    updateHandling(
        handling,
        "assignee_id = ?, version = version + 1, updated_at = ?::timestamptz",
        input.assigneeId(),
        now);

    appendEvent(
        differenceId,
        "ASSIGNED",
        "CLAIMED",
        "CLAIMED",
        "改派责任人：" + previous + " → " + input.assigneeId()
            + (input.reason() == null || input.reason().isBlank() ? "" : "（" + input.reason().trim() + "）"),
        Map.of(
            "fromAssigneeId", String.valueOf(previous),
            "toAssigneeId", input.assigneeId().toString(),
            "reason", trim(input.reason())),
        actor.userId(),
        now);

    return handlingRow(requireHandling(differenceId));
  }

  /**
   * 分类归因（人工分类 + 理由）。
   *
   * <p>⚠️ 分类**可以修正**（人在过程中会改判）—— 每次修正都**追加**一条时间线，
   * 闭环之后冻结（DB 触发器）。
   */
  @Transactional
  public ReconciliationDiffHandling classify(
      UUID differenceId, ReconciliationDiffClassifyInput input, AccessService.Access actor) {
    var difference = requireDifference(differenceId);
    var status = difference.get("status", String.class);
    if ("ADJUSTED".equals(status) || "WRITTEN_OFF".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该差异已闭环（" + statusLabel(status) + "），分类不可再改");
    }

    var category = input.category() == null ? "" : input.category().trim().toUpperCase(Locale.ROOT);
    if (!CATEGORIES.contains(category)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "未知的差异分类：" + input.category() + "（可用：" + String.join("、", CATEGORIES) + "）");
    }
    var reason = trim(input.reason());
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分类理由不能为空（人工分类 + 理由）");
    }

    var now = nowUtc();
    var handling = handlingOf(differenceId);
    if (handling == null) {
      // 🔴 上游已是「已认领」却没有处理记录 = 数据不一致（只有绕开服务层直写库才会出现）。
      //   此时**不能**凭空造一条记录（CLAIMED 态必须有责任人，而责任人无从得知）。
      if (!"OPEN".equals(status)) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "该差异的处理记录缺失（上游状态已是 " + statusLabel(status)
                + "），无法补建 —— 请先让上游回到待处理状态");
      }
      insertHandling(difference, actor.tenantId(), status, null, actor.userId(), now);
      handling = requireHandling(differenceId);
    }
    var previous = handling.get("category", String.class);

    updateHandling(
        handling,
        "category = ?, category_reason = ?, category_set_by = ?, category_set_at = ?::timestamptz,"
            + " version = version + 1, updated_at = ?::timestamptz",
        category,
        reason,
        actor.userId(),
        now,
        now);

    appendEvent(
        differenceId,
        "CLASSIFIED",
        status,
        status,
        (previous == null ? "分类归因：" : "修正分类归因：" + categoryLabel(previous) + " → ")
            + categoryLabel(category) + " —— " + reason,
        Map.of(
            "category", category,
            "previousCategory", previous == null ? "" : previous,
            "reason", reason),
        actor.userId(),
        now);

    return handlingRow(requireHandling(differenceId));
  }

  /**
   * 登记调整分录（**凭证或说明**至少有一个）。
   *
   * <p>🔴 只能在**已认领**的处理记录上登记（没主人就没资格记账 —— DB 触发器也钉死）。
   */
  @Transactional
  public ReconciliationDiffAdjustmentRow recordAdjustment(
      UUID differenceId, ReconciliationDiffAdjustmentInput input, AccessService.Access actor) {
    var difference = requireDifference(differenceId);
    var status = difference.get("status", String.class);
    if (!"CLAIMED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有在「已认领」的差异上才能登记调整分录（当前状态 " + statusLabel(status) + "）");
    }
    var handling = requireHandling(differenceId);

    var voucherNo = trim(input.voucherNo());
    var explanation = trim(input.explanation());
    if (voucherNo.isEmpty() && explanation.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "调整凭证号与说明**至少要有一个**（两者都没有 = 这条分录什么都不是）");
    }

    var amount = money(input.adjustmentAmount());
    if (amount.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "调整金额必须大于 0");
    }

    var currency = trim(input.currency()).toUpperCase(Locale.ROOT);
    if (currency.isEmpty()) {
      currency = difference.get("currency", String.class);
    }
    if (!currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三位大写字母：" + currency);
    }

    var direction = trim(input.direction()).toUpperCase(Locale.ROOT);
    if (!"IN".equals(direction) && !"OUT".equals(direction)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "资金方向只能是 IN 或 OUT：" + input.direction());
    }

    var now = nowUtc();
    var nextNo =
        sql.fetchOne(
                "select coalesce(max(entry_no),0) from finance_reconciliation_adjustment"
                    + " where tenant_id = ? and handling_id = ?",
                actor.tenantId(),
                handling.get("id", UUID.class))
            .get(0, Integer.class)
            + 1;

    var id = UUID.randomUUID();
    insert(
        "finance_reconciliation_adjustment",
        List.of(
            column("id", id),
            column("tenant_id", actor.tenantId()),
            column("handling_id", handling.get("id", UUID.class)),
            column("difference_id", differenceId),
            column("entry_no", nextNo),
            column("voucher_no", voucherNo.isEmpty() ? null : voucherNo),
            column("adjustment_amount", amount),
            column("currency", currency),
            column("direction", direction),
            column("explanation", explanation),
            column("occurred_on", input.occurredOn(), "::date"),
            column("recorded_by", actor.userId()),
            column("recorded_at", now, "::timestamptz"),
            column("created_at", now, "::timestamptz")));

    appendEvent(
        differenceId,
        "ADJUSTMENT_RECORDED",
        "CLAIMED",
        "CLAIMED",
        "登记调整分录 #" + nextNo + "：" + money(amount) + " " + currency
            + (voucherNo.isEmpty() ? "" : "（凭证 " + voucherNo + "）")
            + (explanation.isEmpty() ? "" : " —— " + explanation),
        Map.of(
            "entryNo", nextNo,
            "adjustmentAmount", amount.toPlainString(),
            "currency", currency,
            "direction", direction,
            "voucherNo", voucherNo,
            "explanation", explanation),
        actor.userId(),
        now);

    return adjustmentRow(
        sql.fetchOne("select * from finance_reconciliation_adjustment where id = ?", id));
  }

  /**
   * 人工判定：**已调整**（终态）。
   *
   * <p>🔴 三道闸：① 已分类归因；② **至少一条调整分录**（DB 触发器也钉死一道）；
   * ③ 有闭环结论。
   */
  @Transactional
  public ReconciliationDiffHandling adjust(
      UUID differenceId, ReconciliationDiffDecideInput input, AccessService.Access actor) {
    return decide(differenceId, input, actor, "ADJUSTED", "ADJUSTED");
  }

  /**
   * 人工判定：**已核销**（终态）。
   *
   * <p>核销 = 判定这条差异**不需要调整**（账已经对平或不该动）⇒ 不要求调整分录，
   * 但仍要求：**已分类归因** + **有责任人** + **有结论**。
   */
  @Transactional
  public ReconciliationDiffHandling writeOff(
      UUID differenceId, ReconciliationDiffDecideInput input, AccessService.Access actor) {
    return decide(differenceId, input, actor, "WRITTEN_OFF", "WRITTEN_OFF");
  }

  private ReconciliationDiffHandling decide(
      UUID differenceId,
      ReconciliationDiffDecideInput input,
      AccessService.Access actor,
      String targetStatus,
      String eventType) {
    var difference = requireDifference(differenceId);
    var status = difference.get("status", String.class);
    if (!"CLAIMED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有在「已认领」的差异上才能判定闭环（当前状态 " + statusLabel(status) + "）");
    }
    var handling = requireHandling(differenceId);
    if (handling.get("category", String.class) == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "闭环前必须先做分类归因（没有归因的闭环说不清问题是什么）");
    }
    var conclusion = trim(input.conclusion());
    if (conclusion.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "闭环结论不能为空");
    }
    var adjustmentCount =
        sql.fetchOne(
                "select count(*) from finance_reconciliation_adjustment"
                    + " where tenant_id = ? and handling_id = ?",
                actor.tenantId(),
                handling.get("id", UUID.class))
            .get(0, Long.class);
    if ("ADJUSTED".equals(targetStatus) && adjustmentCount == 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "判定「已调整」必须至少有一条调整分录（凭证或说明）");
    }

    var now = nowUtc();
    // 🔴 **先推上游、再写处理记录**（处理记录的触发器要求两边状态同相）。
    advanceUpstream(difference, targetStatus, actor.tenantId());

    updateHandling(
        handling,
        "status = ?, conclusion = ?, closed_by = ?, closed_at = ?::timestamptz,"
            + " version = version + 1, updated_at = ?::timestamptz",
        targetStatus,
        conclusion,
        actor.userId(),
        now,
        now);

    appendEvent(
        differenceId,
        eventType,
        "CLAIMED",
        targetStatus,
        ("ADJUSTED".equals(targetStatus) ? "判定「已调整」" : "判定「已核销」")
            + " —— " + conclusion
            + ("ADJUSTED".equals(targetStatus)
                ? "（调整分录 " + adjustmentCount + " 条）"
                : "（核销 = 判定不需要调整）"),
        Map.of(
            "conclusion", conclusion,
            "adjustmentCount", adjustmentCount,
            "category", String.valueOf(handling.get("category", String.class))),
        actor.userId(),
        now);

    return handlingRow(requireHandling(differenceId));
  }

  // ================================================================ 动作闸门

  /** 当前可执行的动作 + 每一条的阻断理由（页面据此禁用按钮并说明原因）。 */
  ReconciliationDiffActions actionsOf(
      Record difference, Record handling, List<ReconciliationDiffAdjustmentRow> adjustments) {
    var status = difference.get("status", String.class);
    var open = "OPEN".equals(status);
    var claimed = "CLAIMED".equals(status);
    var closed = "ADJUSTED".equals(status) || "WRITTEN_OFF".equals(status);
    var hasCategory = handling != null && handling.get("category", String.class) != null;
    var hasAdjustment = !adjustments.isEmpty();

    return new ReconciliationDiffActions(
        open,
        open ? "" : "该差异已被处理（" + statusLabel(status) + "），不能再认领",
        claimed,
        claimed ? "" : "只有在「已认领」的差异上才能改派责任人（当前 " + statusLabel(status) + "）",
        !closed,
        closed ? "该差异已闭环，分类不可再改" : "",
        claimed,
        claimed ? "" : "只有在「已认领」的差异上才能登记调整分录（当前 " + statusLabel(status) + "）",
        claimed && hasCategory && hasAdjustment,
        !claimed
            ? "只有在「已认领」的差异上才能判定闭环（当前 " + statusLabel(status) + "）"
            : !hasCategory
                ? "闭环前必须先做分类归因"
                : !hasAdjustment
                    ? "判定「已调整」必须至少有一条调整分录"
                    : "",
        claimed && hasCategory,
        !claimed
            ? "只有在「已认领」的差异上才能判定闭环（当前 " + statusLabel(status) + "）"
            : !hasCategory ? "闭环前必须先做分类归因" : "");
  }

  // ================================================================ 持久化

  /** 推进上游差异的 status（**只准动这一列** —— 其余字段上游触发器冻结）。 */
  private void advanceUpstream(Record difference, String targetStatus, UUID tenantId) {
    var affected =
        sql.execute(
            "update finance_reconciliation_difference set status = ?"
                + " where id = ? and tenant_id = ? and status = ?",
            targetStatus,
            difference.get("id", UUID.class),
            tenantId,
            difference.get("status", String.class));
    if (affected == 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "差异状态已被并发改动（当前 "
              + difference.get("status", String.class)
              + "），请刷新后重试");
    }
  }

  /** 新建处理记录（**状态必须与上游差异同相**）。 */
  private void insertHandling(
      Record difference,
      UUID tenantId,
      String status,
      UUID assigneeId,
      UUID actorId,
      OffsetDateTime now) {
    var claimed = "OPEN".equals(status) ? null : actorId;
    var claimedAt = "OPEN".equals(status) ? null : now;
    insert(
        "finance_reconciliation_handling",
        List.of(
            column("id", UUID.randomUUID()),
            column("tenant_id", tenantId),
            column("difference_id", difference.get("id", UUID.class)),
            column("task_id", difference.get("task_id", UUID.class)),
            column("currency", difference.get("currency", String.class)),
            column("difference_type", difference.get("difference_type", String.class)),
            column("reference", difference.get("reference", String.class)),
            column("assignee_id", assigneeId),
            column("status", status),
            column("claimed_by", claimed),
            column("claimed_at", claimedAt, "::timestamptz"),
            column("version", 0),
            column("created_by", actorId),
            column("created_at", now, "::timestamptz"),
            column("updated_at", now, "::timestamptz")));

    appendEvent(
        difference.get("id", UUID.class),
        "HANDLING_CREATED",
        status,
        status,
        "建立差异处理记录（责任人 " + (assigneeId == null ? "待指派" : assigneeId) + "）",
        Map.of("status", status, "assigneeId", assigneeId == null ? "" : assigneeId.toString()),
        actorId,
        now);
  }

  /** 更新处理记录（**状态列由调用方保证与上游同相**）。 */
  private void updateHandling(Record handling, String assignments, Object... values) {
    var ids = new ArrayList<Object>();
    ids.addAll(java.util.Arrays.asList(values));
    ids.add(handling.get("id", UUID.class));
    sql.execute(
        "update finance_reconciliation_handling set " + assignments + " where id = ?", ids.toArray());
  }

  /** 追加一条时间线（append-only 举证）。 */
  private void appendEvent(
      UUID differenceId,
      String eventType,
      String fromStatus,
      String toStatus,
      String summary,
      Map<String, Object> detail,
      UUID actorId,
      OffsetDateTime now) {
    var handling = handlingOf(differenceId);
    if (handling == null) {
      return;
    }
    var difference = requireDifference(differenceId);
    insert(
        "finance_reconciliation_handling_event",
        List.of(
            column("id", UUID.randomUUID()),
            column("tenant_id", difference.get("tenant_id", UUID.class)),
            column("handling_id", handling.get("id", UUID.class)),
            column("difference_id", differenceId),
            column("task_id", difference.get("task_id", UUID.class)),
            column("event_type", eventType),
            column("from_status", fromStatus),
            column("to_status", toStatus),
            column("summary", summary),
            column("detail", toJson(detail), "::jsonb"),
            column("actor_id", actorId),
            column("occurred_at", now, "::timestamptz"),
            column("created_at", now, "::timestamptz")));
  }

  // ================================================================ 读取

  /** 上游差异（不存在 ⇒ 404）。 */
  Record requireDifference(UUID id) {
    var row = sql.fetchOne("select * from finance_reconciliation_difference where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对账差异不存在：" + id);
    }
    return row;
  }

  /** 队列形状的单条差异（差异 ⇄ 处理记录；不存在 ⇒ 404）。 */
  Record diffRecord(UUID id) {
    var row =
        sql.fetchOne(
            "select " + DIFF_COLUMNS + DIFF_FROM + " where d.id = ?",
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对账差异不存在：" + id);
    }
    return row;
  }

  /** 处理记录（可能还不存在）。 */
  Record handlingOf(UUID differenceId) {
    return sql.fetchOne(
        "select * from finance_reconciliation_handling where difference_id = ?", differenceId);
  }

  /** 处理记录（不存在 ⇒ 409）。 */
  Record requireHandling(UUID differenceId) {
    var row = handlingOf(differenceId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该差异还没有处理记录（先认领或分类）：" + differenceId);
    }
    return row;
  }

  /** 调整分录（按分录序号升序）。 */
  List<ReconciliationDiffAdjustmentRow> adjustmentsOf(UUID differenceId) {
    return sql.fetch(
            "select a.id,a.handling_id,a.entry_no,a.voucher_no,a.adjustment_amount,a.currency,"
                + " a.direction,a.explanation,a.occurred_on,a.recorded_by,a.recorded_at"
                + " from finance_reconciliation_adjustment a"
                + " join finance_reconciliation_handling h"
                + "   on h.tenant_id = a.tenant_id and h.id = a.handling_id"
                + " where h.difference_id = ? order by a.entry_no",
            differenceId)
        .map(this::adjustmentRow);
  }

  /** 时间线（最近的若干条）。 */
  List<ReconciliationDiffEventRow> eventRows(UUID differenceId, int limit) {
    return sql.fetch(
            "select id,handling_id,difference_id,event_type,from_status,to_status,summary,"
                + " actor_id,occurred_at from finance_reconciliation_handling_event"
                + " where difference_id = ? order by occurred_at desc, id desc limit ?",
            differenceId,
            limit)
        .map(this::eventRow);
  }

  // ================================================================ 行映射

  ReconciliationDiffRow diffRow(Record r) {
    var differenceType = r.get("difference_type", String.class);
    var side = r.get("side", String.class);
    var status = r.get("status", String.class);
    var category = r.get("category", String.class);
    return new ReconciliationDiffRow(
        r.get("difference_id", UUID.class),
        r.get("task_id", UUID.class),
        differenceType,
        differenceTypeLabel(differenceType),
        side,
        sideLabel(side),
        r.get("book_source", String.class),
        r.get("group_key", UUID.class),
        r.get("external_kind", String.class),
        r.get("external_line_id", UUID.class),
        r.get("book_line_id", UUID.class),
        r.get("occurred_on", LocalDate.class),
        r.get("reference", String.class),
        r.get("transaction_type", String.class),
        r.get("external_amount", BigDecimal.class),
        r.get("book_amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("direction", String.class),
        r.get("difference_amount", BigDecimal.class),
        r.get("exchange_rate_available", Boolean.class),
        r.get("attribution", String.class),
        r.get("reason", String.class),
        status,
        statusLabel(status),
        r.get("handling_id", UUID.class),
        category,
        category == null ? null : categoryLabel(category),
        r.get("category_reason", String.class),
        r.get("assignee_id", UUID.class),
        r.get("claimed_at", OffsetDateTime.class),
        r.get("conclusion", String.class),
        r.get("closed_at", OffsetDateTime.class),
        r.get("adjustment_count", Integer.class) == null ? 0 : r.get("adjustment_count", Integer.class));
  }

  ReconciliationDiffHandling handlingRow(Record r) {
    var status = r.get("status", String.class);
    var category = r.get("category", String.class);
    return new ReconciliationDiffHandling(
        r.get("id", UUID.class),
        r.get("difference_id", UUID.class),
        r.get("task_id", UUID.class),
        status,
        statusLabel(status),
        category,
        category == null ? null : categoryLabel(category),
        r.get("category_reason", String.class),
        r.get("category_set_by", UUID.class),
        r.get("category_set_at", OffsetDateTime.class),
        r.get("assignee_id", UUID.class),
        r.get("claimed_by", UUID.class),
        r.get("claimed_at", OffsetDateTime.class),
        r.get("conclusion", String.class),
        r.get("closed_by", UUID.class),
        r.get("closed_at", OffsetDateTime.class),
        r.get("version", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  ReconciliationDiffAdjustmentRow adjustmentRow(Record r) {
    var direction = r.get("direction", String.class);
    return new ReconciliationDiffAdjustmentRow(
        r.get("id", UUID.class),
        r.get("handling_id", UUID.class),
        r.get("entry_no", Integer.class),
        r.get("voucher_no", String.class),
        r.get("adjustment_amount", BigDecimal.class),
        r.get("currency", String.class),
        direction,
        directionLabel(direction),
        r.get("explanation", String.class),
        r.get("occurred_on", LocalDate.class),
        r.get("recorded_by", UUID.class),
        r.get("recorded_at", OffsetDateTime.class));
  }

  ReconciliationDiffEventRow eventRow(Record r) {
    var eventType = r.get("event_type", String.class);
    return new ReconciliationDiffEventRow(
        r.get("id", UUID.class),
        r.get("handling_id", UUID.class),
        r.get("difference_id", UUID.class),
        eventType,
        eventLabel(eventType),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("summary", String.class),
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 筛选条件

  /** 队列的筛选条件（**表别名固定 d = 差异、h = 处理记录**）。 */
  record Filter(String where, List<Object> args) {}

  private Filter buildFilter(
      UUID taskId,
      String status,
      String category,
      String currency,
      UUID assigneeId,
      String differenceType,
      String keyword) {
    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();

    if (taskId != null) {
      where.append(" and d.task_id = ?");
      args.add(taskId);
    }
    if (status != null && !status.isBlank()) {
      where.append(" and d.status = ?");
      args.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (category != null && !category.isBlank()) {
      where.append(" and h.category = ?");
      args.add(category.trim().toUpperCase(Locale.ROOT));
    }
    if (currency != null && !currency.isBlank()) {
      where.append(" and d.currency = ?");
      args.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    if (assigneeId != null) {
      where.append(" and h.assignee_id = ?");
      args.add(assigneeId);
    }
    if (differenceType != null && !differenceType.isBlank()) {
      where.append(" and d.difference_type = ?");
      args.add(differenceType.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      var kw = keyword.trim().toLowerCase(Locale.ROOT);
      where.append(
          " and (position(? in lower(d.reference)) > 0"
              + " or position(? in lower(d.attribution)) > 0"
              + " or position(? in lower(d.reason)) > 0"
              + " or position(? in lower(coalesce(h.category_reason,''))) > 0"
              + " or position(? in lower(coalesce(h.conclusion,''))) > 0)");
      args.add(kw);
      args.add(kw);
      args.add(kw);
      args.add(kw);
      args.add(kw);
    }
    return new Filter(where.toString(), args);
  }

  // ================================================================ 工具

  /** 一列（值 + 可选的 PG 类型转换）。 */
  record Column(String name, Object value, String cast) {}

  static Column column(String name, Object value) {
    return new Column(name, value, null);
  }

  static Column column(String name, Object value, String cast) {
    return new Column(name, value, cast);
  }

  /**
   * 安全的 INSERT：列名与占位符**由列清单生成**。
   *
   * <p>⚠️ 手写 VALUES 列表时列数与占位符个数极易对不上（对不上 ⇒ 迁移一跑就炸，
   * 而这类错误在编译期完全看不出来）。
   */
  private void insert(String table, List<Column> columns) {
    var names = new StringBuilder();
    var placeholders = new StringBuilder();
    var values = new ArrayList<Object>();
    for (var column : columns) {
      if (names.length() > 0) {
        names.append(",");
        placeholders.append(",");
      }
      names.append(column.name());
      placeholders.append(column.cast() == null ? "?" : "?" + column.cast());
      values.add(column.value());
    }
    sql.execute(
        "insert into " + table + "(" + names + ") values (" + placeholders + ")",
        values.toArray());
  }

  static OffsetDateTime nowUtc() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  static BigDecimal money(BigDecimal value) {
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  static String statusLabel(String status) {
    return switch (status) {
      case "OPEN" -> "待处理";
      case "CLAIMED" -> "已认领";
      case "ADJUSTED" -> "已调整";
      case "WRITTEN_OFF" -> "已核销";
      default -> status;
    };
  }

  static String categoryLabel(String category) {
    return switch (category) {
      case "BOOK_OVER_RECORDED" -> "我方多记";
      case "COUNTERPARTY_OVER_RECORDED" -> "对方多记";
      case "TIMING_DIFFERENCE" -> "时间差";
      case "FX_DIFFERENCE" -> "汇率差";
      case "FEE_OMISSION" -> "手续费遗漏";
      default -> category;
    };
  }

  static String differenceTypeLabel(String type) {
    return switch (type) {
      case "EXTERNAL_ONLY" -> "平台账单多出";
      case "BOOK_ONLY" -> "我方账多出";
      case "AMOUNT_MISMATCH" -> "金额对不齐";
      case "CURRENCY_MISMATCH" -> "币种不一致";
      case "DIMENSION_MISMATCH" -> "维度对不上";
      default -> type;
    };
  }

  static String sideLabel(String side) {
    return switch (side) {
      case "EXTERNAL" -> "平台账单多出";
      case "BOOK" -> "我方账多出";
      case "BOTH" -> "两边都参与了但没配平";
      default -> side;
    };
  }

  static String directionLabel(String direction) {
    return switch (direction) {
      case "IN" -> "收入";
      case "OUT" -> "支出";
      default -> direction;
    };
  }

  static String eventLabel(String eventType) {
    return switch (eventType) {
      case "HANDLING_CREATED" -> "建立处理记录";
      case "ASSIGNED" -> "指定责任人";
      case "CLASSIFIED" -> "分类归因";
      case "ADJUSTMENT_RECORDED" -> "登记调整分录";
      case "ADJUSTED" -> "判定已调整";
      case "WRITTEN_OFF" -> "判定已核销";
      default -> eventType;
    };
  }

  /** jsonb 序列化（事件详情）。 */
  static String toJson(Map<String, Object> detail) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(detail);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return "{}";
    }
  }
}
