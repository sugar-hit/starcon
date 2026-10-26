package com.starcon.finance;

import static com.starcon.finance.BadDebtModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.1.1.3 坏账与核销。
 *
 * <p>蓝图 12-finance.md：「处理坏账：争议损失确认、买家拒付、平台冻结无法收回的核销，保留核销依据与审批。」输入：「核销申请；依据材料；审批。」输出：「坏账核销记录 + 会计处理 + 审计留痕。」
 *
 * <p><b>本模块的六条口径纪律</b>（每一条都在下面有对应实现）：
 *
 * <ol>
 *   <li><b>核销是终审动作。</b>一张申请必须走完「申请 → 依据 → 提交 → 审批 → 核销」，
 *       其中<b>审批人 ≠ 提交人</b>、<b>审批意见必填</b>、<b>提交前至少一条依据材料</b>。
 *       服务层与 DB 各判一次（DB 的六道守卫见 V342）。
 *   <li><b>没有「一键批量核销」。</b>蓝图写死「不允许批量一键核销无审批」⇒ 本模块
 *       <b>不提供任何批量端点</b>，状态机也只能一张一张走。
 *   <li><b>核销路径 = 作废 + 新单，一个字节都不写 {@code settled_amount}。</b>
 *       V338 的注释原文：「只放行 settled_amount（核销金额）—— 那是 <b>12.2.1.2 的写入所有权</b>」。
 *       ⇒ 本模块把不可收回的金额从应收账上**移走**的方式是：作废原单 + 为仍可收回的余额新建承接单。
 *       <b>已部分回款的应收（settled_amount &gt; 0）本模块不受理</b> —— 冲减「已核销」那部分属
 *       12.2.1.2 核销的冲销（红冲）范围。
 *   <li><b>会计处理不可用。</b>下游 12.5.2.1「凭证管理」未交付 ⇒ {@code accountingStatus} 恒为
 *       {@code PENDING_FINANCE}、{@code accountingRef} 恒为空串、{@code accountingOwnerModule} 恒为
 *       {@code 12.5.2.1}。<b>绝不填 0、绝不造凭证、绝不假装已入账。</b>
 *   <li><b>依据链要能自证。</b>依据材料是独立的一层（一项材料有自己的类型 / 标题 / 外部单号 / 内容 /
 *       附件 / 日期），提交后<b>不可再动</b> —— 审批人看到的依据必须与提交时逐字相同。
 *   <li><b>上游纠纷走授权 API。</b>11.4.1.1 纠纷在 trade 服务 ⇒ 只经 {@link TradeDisputePort}
 *       的 HTTP 读，<b>不跨库 SQL / 不跨库 FK / 不跨库视图</b>；读到的单号 / 状态 / 金额以快照落库。
 * </ol>
 *
 * <p><b>多租户</b>：租户来自已认证成员关系（{@link AccessService}），本模块的每一条 SQL 都跑在
 * 绑定了 {@code app.current_tenant} 的事务里，由 RLS 在数据库层强制隔离。
 */
@Service
public class BadDebtService {

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 金额精度（NUMERIC(19,4)）。 */
  static final int MONEY_SCALE = 4;

  private final DSLContext sql;
  private final ReceivableService receivables;
  private final TradeDisputePort disputes;

  public BadDebtService(DSLContext sql, ReceivableService receivables, TradeDisputePort disputes) {
    this.sql = sql;
    this.receivables = receivables;
    this.disputes = disputes;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 特别地，「会计处理」与「批量核销」两行**必须如实报不可用** ——
   * 把它们写成可用，界面就会给用户一个点了没反应的按钮，或者一个假的「已入账」标记。
   */
  public FinanceBadDebtCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceBadDebtCapability>();
    var gaps = new ArrayList<FinanceBadDebtGap>();

    capabilities.add(
        new FinanceBadDebtCapability(
            "12.1.1.3",
            "坏账核销申请 + 依据 + 审批",
            true,
            "finance_bad_debt_case + finance_bad_debt_evidence + finance_bad_debt_event",
            "",
            "核销申请（申请单）、依据材料（可多项）、审批（审批人 ≠ 提交人、意见必填）三样输入"
                + "各有各的落法。状态机 DRAFT → PENDING_APPROVAL → APPROVED → WRITTEN_OFF，"
                + "非法转换在 DB 层写不出来。"));
    capabilities.add(
        new FinanceBadDebtCapability(
            "12.1.1.3",
            "终审动作的六道 DB 守卫",
            true,
            "V342 的六个守卫 + 服务层各判一次",
            "",
            "① 新单必须先是草稿 ② 终态整行冻结 ③ 状态机只允许合法转换 "
                + "④ 提交审批必须有依据材料 ⑤ 审批人 ≠ 提交人且审批意见必填 "
                + "⑥ 会计处理不可用（不造凭证）。直连 SQL 也一样被拒。"));
    capabilities.add(
        new FinanceBadDebtCapability(
            "12.1.1.3",
            "核销执行（作废 + 新单）",
            true,
            "12.1.1.1 的作废与建单能力（同一个服务、同一个库）",
            "",
            "不可收回的金额通过「作废原应收单 + 为仍可收回的余额新建承接单」移出应收账。"
                + "本模块**不写 finance_receivable_line.settled_amount** —— 那一列的写者恒为 12.2.1.2。"));
    capabilities.add(
        new FinanceBadDebtCapability(
            "11.4.1.1",
            "纠纷列表（上游）",
            true,
            "trade 的授权 HTTP API：GET /trade/platform-disputes/{id}",
            "",
            "争议损失确认的坏账单可引用一张平台纠纷：经 trade 的**授权 API**读（不跨库 SQL / FK / 视图），"
                + "把纠纷单号 / 状态 / 金额以**快照**落库，依据链因此能自证。"
                + "「争议损失确认」要求上游纠纷已是**我方败诉（RESOLVED_LOST）** —— 未出裁决的纠纷不能核销。"));
    capabilities.add(
        new FinanceBadDebtCapability(
            "12.1.1.2",
            "应收账龄（上游）",
            true,
            "12.1.1.1 的 finance_receivable（只读）",
            "",
            "坏账核销的目标是 12.1.1.1 的应收单；账龄分布读的是同一张单。"
                + "本模块把已核销的应收**作废**掉 ⇒ 该笔金额**自动**从账龄分布里消失，"
                + "不需要改 12.1.1.2 的任何一列或一条 SQL。"));
    capabilities.add(
        new FinanceBadDebtCapability(
            "12.5.2.1",
            "会计处理（凭证）",
            false,
            "下游未交付：accountingStatus 恒为 PENDING_FINANCE，凭证号恒为空串",
            "12.5.2.1",
            "蓝图输出里的「会计处理」当前**不可产生**：本模块只产出**坏账核销记录**（金额 / 事由 / 依据 / 审批），"
                + "凭证由 12.5.2.1 据此生成。**绝不填 0、绝不造凭证、绝不假装已入账** —— "
                + "「会计处理不可用」表现为「归属模块 12.5.2.1 + 空凭证号」，不是一个 0。"));
    capabilities.add(
        new FinanceBadDebtCapability(
            "12.2.1.2",
            "核销（settled_amount 的写者）",
            false,
            "写路径属 12.2.1.2；本模块走作废 + 新单，一个字节都不写 settled_amount",
            "12.2.1.2",
            "V338 的注释原文：「只放行 settled_amount（核销金额）—— 那是 **12.2.1.2 的写入所有权**」。"
                + "⇒ 本模块**不受理已部分回款的应收**（settled_amount > 0）：冲减「已核销」那部分属 "
                + "12.2.1.2 核销的冲销（红冲）范围。两条车道在核销金额上不撞同一列。"));
    capabilities.add(
        new FinanceBadDebtCapability(
            "12.1.1.3",
            "批量一键核销",
            false,
            "蓝图加粗句写死：不允许批量一键核销无审批",
            "",
            "本模块**刻意不提供任何批量核销端点**。每一次核销都必须有申请、有依据材料、"
                + "有审批（且审批人 ≠ 提交人、意见必填）。"));

    gaps.add(
        new FinanceBadDebtGap(
            "12.5.2.1", "凭证管理（会计处理）", "finance", false, "12.5 总账与报表",
            "会计处理不可用：核销记录里只有「归属模块 12.5.2.1 + 空凭证号」。"
                + "12.5.2.1 落地后读本模块的核销记录生成凭证，并 DROP 掉那条 CHECK，不需要改表结构。"));
    gaps.add(
        new FinanceBadDebtGap(
            "12.2.1.2", "核销（收款核销到明细行）", "finance", false, "12.2 收付款",
            "settled_amount 的写者恒为 12.2.1.2；本模块走作废 + 新单。"
                + "已部分回款的应收的冲减由 12.2.1.2 承接。"));
    gaps.add(
        new FinanceBadDebtGap(
            "13.1.1.1", "利润与经营分析", "analytics", false, "13 利润与经营分析",
            "13 未交付：本模块的核销记录（金额 / 事由 / 币种）是它的一处上游输入。"));
    gaps.add(
        new FinanceBadDebtGap(
            "12.1.1.1", "应收单管理（上游）", "finance", true, "12.1 应收与资金",
            "目标应收单来自 12.1.1.1；本模块对它**只读 + 快照**，核销时通过它自己的作废与建单能力执行。"));

    return new FinanceBadDebtCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询）+ 本模块的枚举词表。 */
  public FinanceBadDebtOptions options() {
    var currencies =
        sql.fetch("select distinct currency from finance_bad_debt_case order by currency")
            .map(r -> r.get(0, String.class));
    var shops =
        sql.fetch(
                "select shop_id, shop_name, count(*) as cnt from finance_bad_debt_case"
                    + " where shop_id is not null group by shop_id, shop_name order by cnt desc, shop_name")
            .map(
                r ->
                    new FinanceBadDebtShopOption(
                        r.get("shop_id", UUID.class),
                        r.get("shop_name", String.class),
                        r.get("cnt", Long.class)));

    var reasonTypeOptions = new ArrayList<FinanceBadDebtReasonTypeOption>();
    for (var t : BadDebtReasonType.values()) {
      reasonTypeOptions.add(new FinanceBadDebtReasonTypeOption(t.name(), t.label()));
    }
    var statusOptions = new ArrayList<FinanceBadDebtStatusOption>();
    for (var s : BadDebtStatus.values()) {
      statusOptions.add(new FinanceBadDebtStatusOption(s.name(), s.label()));
    }
    var evidenceTypeOptions = new ArrayList<FinanceBadDebtEvidenceTypeOption>();
    for (var e : BadDebtEvidenceType.values()) {
      evidenceTypeOptions.add(new FinanceBadDebtEvidenceTypeOption(e.name(), e.label()));
    }

    return new FinanceBadDebtOptions(
        List.of(),
        List.of(),
        currencies,
        shops,
        reasonTypeOptions,
        statusOptions,
        evidenceTypeOptions);
  }

  // ================================================================ 列表 / 详情

  /**
   * 申请单列表：真分页 + 真筛选 + 真排序 + 汇总。
   *
   * <p>⚠️ **汇总覆盖整个筛选结果集**，不是当前页：分页一变汇总就变的话，「翻到第二页看到总核销额变小了」会被读成「数据丢了」。
   */
  public FinanceBadDebtPage page(BadDebtQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (query.status() != null) {
      where.add("c.status = ?");
      binds.add(query.status().name());
    }
    if (query.reasonType() != null) {
      where.add("c.reason_type = ?");
      binds.add(query.reasonType().name());
    }
    if (query.currency() != null && !query.currency().isBlank()) {
      where.add("c.currency = ?");
      binds.add(query.currency().trim());
    }
    if (query.receivableId() != null) {
      where.add("c.receivable_id = ?");
      binds.add(query.receivableId());
    }
    if (query.disputeId() != null) {
      where.add("c.dispute_id = ?");
      binds.add(query.disputeId());
    }
    if (query.shopId() != null) {
      where.add("c.shop_id = ?");
      binds.add(query.shopId());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add(
          "(c.case_no ilike ? or c.receivable_no ilike ? or c.counterparty_name ilike ?"
              + " or c.dispute_no ilike ? or c.reason_detail ilike ?)");
      var like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    var whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from finance_bad_debt_case c" + whereSql, concat(binds))
            .get(0, Long.class);

    var items =
        sql.fetch(
                "select c.* from finance_bad_debt_case c"
                    + whereSql
                    + " order by coalesce(c.submitted_at, c.created_at) desc, c.id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::row);

    var totals =
        sql.fetch(
                "select c.currency, count(*) as cnt, coalesce(sum(c.write_off_amount),0) as amount,"
                    + " count(*) filter (where c.status = 'WRITTEN_OFF') as written"
                    + " from finance_bad_debt_case c"
                    + whereSql
                    + " group by c.currency order by c.currency",
                concat(binds))
            .map(
                r ->
                    new FinanceBadDebtCurrencyTotal(
                        r.get("currency", String.class),
                        r.get("cnt", Long.class),
                        money(r.get("amount", BigDecimal.class)),
                        r.get("written", Long.class)));

    return new FinanceBadDebtPage(items, p, s, total, totals);
  }

  /** 坏账汇总：按状态 / 事由 / 币种。 */
  public FinanceBadDebtSummary summary() {
    long total = sql.fetchOne("select count(*) from finance_bad_debt_case").get(0, Long.class);
    long pending =
        sql.fetchOne(
                "select count(*) from finance_bad_debt_case where status = 'PENDING_APPROVAL'")
            .get(0, Long.class);
    long approved =
        sql.fetchOne("select count(*) from finance_bad_debt_case where status = 'APPROVED'")
            .get(0, Long.class);
    long writtenOff =
        sql.fetchOne("select count(*) from finance_bad_debt_case where status = 'WRITTEN_OFF'")
            .get(0, Long.class);
    long rejected =
        sql.fetchOne("select count(*) from finance_bad_debt_case where status = 'REJECTED'")
            .get(0, Long.class);

    var currencyTotals =
        sql.fetch(
                "select currency, count(*) as cnt, coalesce(sum(write_off_amount),0) as amount,"
                    + " count(*) filter (where status = 'WRITTEN_OFF') as written"
                    + " from finance_bad_debt_case group by currency order by currency")
            .map(
                r ->
                    new FinanceBadDebtCurrencyTotal(
                        r.get("currency", String.class),
                        r.get("cnt", Long.class),
                        money(r.get("amount", BigDecimal.class)),
                        r.get("written", Long.class)));

    var reasonTotals = new ArrayList<FinanceBadDebtReasonTotal>();
    for (var t : BadDebtReasonType.values()) {
      var row =
          sql.fetchOne(
              "select count(*) as cnt, coalesce(sum(write_off_amount),0) as amount"
                  + " from finance_bad_debt_case where reason_type = ?",
              t.name());
      if (row == null) continue;
      reasonTotals.add(
          new FinanceBadDebtReasonTotal(
              t, t.label(), row.get("cnt", Long.class), money(row.get("amount", BigDecimal.class))));
    }

    return new FinanceBadDebtSummary(
        total,
        pending,
        approved,
        writtenOff,
        rejected,
        currencyTotals,
        reasonTotals,
        "汇总按币种分组，**不跨币种合计**（USD 与 EUR 加起来不是任何一个金额）。");
  }

  /** 申请单详情：单头 + 依据材料 + 审计留痕 + 当前可执行动作。 */
  public FinanceBadDebtDetail detail(UUID id, AccessService.Access actor) {
    var head = require(id);
    var evidence =
        sql.fetch(
                "select * from finance_bad_debt_evidence where case_id = ? and status = 'ACTIVE'"
                    + " order by evidence_no, id",
                id)
            .map(this::evidenceRow);
    var events =
        sql.fetch(
                "select * from finance_bad_debt_event where case_id = ? order by occurred_at, id", id)
            .map(this::eventRow);
    var r = row(head);
    return new FinanceBadDebtDetail(r, evidence, events, actions(r, evidence.size(), actor));
  }

  /** 当前可执行动作（含**为什么不可执行**）。 */
  private List<FinanceBadDebtAction> actions(
      FinanceBadDebtRow r, int evidenceCount, AccessService.Access actor) {
    var list = new ArrayList<FinanceBadDebtAction>();
    boolean draft = r.status() == BadDebtStatus.DRAFT;
    boolean pending = r.status() == BadDebtStatus.PENDING_APPROVAL;
    boolean approved = r.status() == BadDebtStatus.APPROVED;
    boolean terminal = r.status().terminal();

    list.add(
        new FinanceBadDebtAction(
            "ADD_EVIDENCE",
            "加依据材料",
            draft,
            "POST",
            "/finance/bad-debt/" + r.id() + "/evidence",
            draft ? "" : "只有草稿可以补依据材料（当前 " + r.status().label() + "）"));
    list.add(
        new FinanceBadDebtAction(
            "SUBMIT",
            "提交审批",
            draft && evidenceCount > 0,
            "POST",
            "/finance/bad-debt/" + r.id() + "/submit",
            !draft
                ? "只有草稿可以提交审批（当前 " + r.status().label() + "）"
                : "还没有依据材料：核销是终审动作，必须有完整依据链"));
    list.add(
        new FinanceBadDebtAction(
            "APPROVE",
            "审批通过",
            pending && !isSubmitter(r, actor),
            "POST",
            "/finance/bad-debt/" + r.id() + "/approve",
            !pending
                ? "只有待审批的申请可以审批（当前 " + r.status().label() + "）"
                : "你是这张申请的提交人：审批人不能是提交人，核销是终审动作"));
    list.add(
        new FinanceBadDebtAction(
            "REJECT",
            "审批驳回",
            pending,
            "POST",
            "/finance/bad-debt/" + r.id() + "/reject",
            pending ? "" : "只有待审批的申请可以驳回（当前 " + r.status().label() + "）"));
    list.add(
        new FinanceBadDebtAction(
            "WRITE_OFF",
            "执行核销",
            approved,
            "POST",
            "/finance/bad-debt/" + r.id() + "/write-off",
            approved ? "" : "只有已批准待核销的申请可以执行核销（当前 " + r.status().label() + "）"));
    list.add(
        new FinanceBadDebtAction(
            "VOID",
            "作废申请",
            !terminal && r.status() != BadDebtStatus.WRITTEN_OFF,
            "POST",
            "/finance/bad-debt/" + r.id() + "/void",
            terminal ? "终态不可作废（当前 " + r.status().label() + "）" : ""));
    return List.copyOf(list);
  }

  private boolean isSubmitter(FinanceBadDebtRow r, AccessService.Access actor) {
    return r.submittedBy() != null && actor != null && r.submittedBy().equals(actor.userId());
  }

  // ================================================================ 建单

  /**
   * 提交坏账核销申请（草稿）。
   *
   * <p>⚠️ 目标应收单必须**已确认且一分未收**：
   *
   * <ul>
   *   <li>已部分回款的应收（settled_amount &gt; 0）**不受理** —— 冲减「已核销」那部分属 12.2.1.2 核销的冲销（红冲）范围，
   *       而本模块写 settled_amount 会与 12.2.1.2 抢同一列的写权、并污染「已收回应收」的口径。
   *   <li>核销金额不得超过该应收的**未核销余额**。
   * </ul>
   *
   * @param bearer 调用方自己的会话 —— 引用上游纠纷（11.4.1.1）时要转发给 trade。
   */
  @Transactional
  public FinanceBadDebtRow create(
      FinanceBadDebtInput input, AccessService.Access actor, String bearer) {
    if (input == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    if (input.receivableId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定要核销的应收单");
    }
    if (input.reasonType() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "坏账事由必填");
    }
    String detail = trim(input.reasonDetail());
    if (detail.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "坏账事由必须写明明细");
    }
    BigDecimal amount = money(input.writeOffAmount());
    if (amount == null || amount.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "核销金额必须大于 0");
    }

    var target = receivableOf(input.receivableId());
    BigDecimal settled = target.get("settled_amount", BigDecimal.class);
    if (settled.signum() > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "这张应收已回款 "
              + settled.toPlainString()
              + "：冲减「已核销」那部分属 12.2.1.2 核销的冲销（红冲）路径，"
              + "本模块只核销从未收到的部分，且不写 settled_amount（那一列的写者恒为 12.2.1.2）");
    }
    BigDecimal open = openBalance(target);
    if (amount.compareTo(open) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "核销金额 "
              + amount.toPlainString()
              + " 超过该应收的未核销余额 "
              + open.toPlainString()
              + "：核销金额不能超过未收回的部分");
    }

    // 上游纠纷（11.4.1.1，trade 服务）：只经授权 HTTP API 读，快照落库。
    String disputeNo = "";
    String disputeStatus = "";
    BigDecimal disputeAmount = null;
    String disputeCurrency = null;
    if (input.disputeId() != null) {
      var dispute = disputes.dispute(input.disputeId(), bearer);
      disputeNo = dispute.disputeNo() == null ? "" : dispute.disputeNo();
      disputeStatus = dispute.disputeStatus() == null ? "" : dispute.disputeStatus();
      disputeAmount = money(dispute.disputeAmount());
      disputeCurrency = dispute.disputeCurrency();
      if (disputeCurrency != null
          && !disputeCurrency.equals(target.get("currency", String.class))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "纠纷 " + disputeNo + " 的币种是 " + disputeCurrency + "，与目标应收的币种不一致");
      }
      if (input.reasonType() == BadDebtReasonType.DISPUTE_LOSS && !"RESOLVED_LOST".equals(disputeStatus)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "争议损失确认要求上游纠纷已是**我方败诉**（RESOLVED_LOST），当前状态是 " + disputeStatus);
      }
    }

    var id = UUID.randomUUID();
    var no = nextReference("BD");
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_bad_debt_case(id,tenant_id,case_no,receivable_id,receivable_no,"
            + " shop_id,shop_name,counterparty_ref,counterparty_name,currency,"
            + " receivable_amount,receivable_settled,open_balance,write_off_amount,"
            + " reason_type,reason_detail,dispute_id,dispute_no,dispute_status,dispute_amount,"
            + " dispute_currency,status,decision_comment,accounting_status,accounting_owner_module,"
            + " accounting_ref,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'DRAFT','', 'PENDING_FINANCE','12.5.2.1','',?,"
            + " ?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        no,
        target.get("id", UUID.class),
        target.get("receivable_no", String.class),
        target.get("shop_id", UUID.class),
        target.get("shop_name", String.class),
        target.get("counterparty_ref", UUID.class),
        target.get("counterparty_name", String.class),
        target.get("currency", String.class),
        target.get("amount", BigDecimal.class),
        target.get("settled_amount", BigDecimal.class),
        open,
        amount,
        input.reasonType().name(),
        detail,
        input.disputeId(),
        disputeNo,
        disputeStatus,
        disputeAmount,
        disputeCurrency,
        trim(input.note()),
        now,
        now);

    writeEvent(
        actor.tenantId(),
        id,
        "CREATED",
        "",
        "DRAFT",
        "申请坏账核销：" + input.reasonType().label() + " " + amount.toPlainString(),
        actor.userId(),
        now);
    return row(require(id));
  }

  // ================================================================ 依据材料

  /** 加一条依据材料（只有草稿可以）。 */
  @Transactional
  public FinanceBadDebtDetail addEvidence(
      UUID id, FinanceBadDebtEvidenceInput input, AccessService.Access actor) {
    var head = require(id);
    if (!"DRAFT".equals(head.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "只有草稿可以加依据材料（当前 " + statusLabel(head.get("status", String.class)) + "）");
    }
    if (input == null || input.evidenceType() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "依据材料类型必填");
    }
    String title = trim(input.title());
    if (title.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "依据材料必须写明标题");
    }
    String content = trim(input.content());
    if (content.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "依据材料必须写明内容：没有内容的依据不是依据");
    }

    int nextNo =
        sql.fetchOne(
                "select coalesce(max(evidence_no),0) + 1 as n from finance_bad_debt_evidence where case_id = ?",
                id)
            .get("n", Integer.class);
    var evidenceId = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_bad_debt_evidence(id,tenant_id,case_id,evidence_no,evidence_type,title,"
            + " reference_no,content,attachment_ref,occurred_on,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::date,?,?::timestamptz)",
        evidenceId,
        actor.tenantId(),
        id,
        nextNo,
        input.evidenceType().name(),
        title,
        trim(input.referenceNo()),
        content,
        trim(input.attachmentRef()),
        input.occurredOn(),
        actor.userId(),
        now);

    writeEvent(
        actor.tenantId(),
        id,
        "EVIDENCE_ADDED",
        head.get("status", String.class),
        head.get("status", String.class),
        "加依据材料：" + input.evidenceType().label() + " " + title,
        actor.userId(),
        now);
    return detail(id, actor);
  }

  /**
   * 移除一条依据材料（只有草稿可以）。
   *
   * <p>⚠️ **软移除，不物理删除**：依据材料是「举证凭据」，删掉就没有痕迹了 ——
   * 与 12.1.1.1 的催收任务同一手法（取消走 {@code status='CANCELLED'}）。
   * 运行角色对这张表的 DELETE 权限是收回的，物理删除在本服务里**根本做不到**；
   * 移除动作本身作为 {@code EVIDENCE_REMOVED} 事件留在留痕里。
   */
  @Transactional
  public FinanceBadDebtDetail removeEvidence(
      UUID id, UUID evidenceId, AccessService.Access actor) {
    var head = require(id);
    if (!"DRAFT".equals(head.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "只有草稿可以移除依据材料（当前 " + statusLabel(head.get("status", String.class)) + "）");
    }
    var evidence =
        sql.fetchOne(
            "select * from finance_bad_debt_evidence where id = ? and case_id = ? and status = 'ACTIVE'",
            evidenceId,
            id);
    if (evidence == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "依据材料不存在或不属于这张申请");
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_bad_debt_evidence set status='REMOVED', removed_at=?::timestamptz, removed_by=?"
            + " where id = ?",
        now,
        actor.userId(),
        evidenceId);
    writeEvent(
        actor.tenantId(),
        id,
        "EVIDENCE_REMOVED",
        head.get("status", String.class),
        head.get("status", String.class),
        "移除依据材料：" + evidence.get("title", String.class),
        actor.userId(),
        now);
    return detail(id, actor);
  }

  // ================================================================ 提交 / 审批

  /** 提交审批。**必须**已有至少一条依据材料（服务层与 DB 各判一次）。 */
  @Transactional
  public FinanceBadDebtRow submit(UUID id, AccessService.Access actor, String bearer) {
    var head = require(id);
    var status = head.get("status", String.class);
    if (!"DRAFT".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "只有草稿可以提交审批（当前 " + statusLabel(status) + "）");
    }
    long evidenceCount =
        sql.fetchOne(
                "select count(*) as n from finance_bad_debt_evidence where case_id = ? and status = 'ACTIVE'",
                id)
            .get("n", Long.class);
    if (evidenceCount == 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "提交审批前必须至少有一条依据材料：核销是终审动作，需要完整依据链");
    }
    // 上游纠纷在提交这一刻再确认一次：期间若已翻案（不再是败诉），就不该进审批队列。
    if (head.get("dispute_id", UUID.class) != null) {
      var dispute = disputes.dispute(head.get("dispute_id", UUID.class), bearer);
      if (!"RESOLVED_LOST".equals(dispute.disputeStatus())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "上游纠纷 "
                + head.get("dispute_no", String.class)
                + " 当前状态是 "
                + dispute.disputeStatus()
                + "，不再是「我方败诉」：争议损失确认需要以败诉结论为准");
      }
    }

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_bad_debt_case set status='PENDING_APPROVAL', submitted_by=?, submitted_at=?::timestamptz,"
            + " updated_at=?::timestamptz where id = ?",
        actor.userId(),
        now,
        now,
        id);
    writeEvent(
        actor.tenantId(), id, "SUBMITTED", status, "PENDING_APPROVAL", "提交审批", actor.userId(), now);
    return row(require(id));
  }

  /** 审批通过。**审批人 ≠ 提交人**，**审批意见必填**。 */
  @Transactional
  public FinanceBadDebtRow approve(UUID id, FinanceBadDebtDecisionInput input, AccessService.Access actor) {
    return decide(id, input, actor, true);
  }

  /** 审批驳回。**审批意见必填**。 */
  @Transactional
  public FinanceBadDebtRow reject(UUID id, FinanceBadDebtDecisionInput input, AccessService.Access actor) {
    return decide(id, input, actor, false);
  }

  private FinanceBadDebtRow decide(
      UUID id, FinanceBadDebtDecisionInput input, AccessService.Access actor, boolean approved) {
    var head = require(id);
    var status = head.get("status", String.class);
    if (!"PENDING_APPROVAL".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "只有待审批的申请可以审批（当前 " + statusLabel(status) + "）");
    }
    String comment = input == null ? "" : trim(input.comment());
    if (comment.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批意见必填：没有意见的审批无法追溯依据");
    }
    var submittedBy = head.get("submitted_by", UUID.class);
    if (submittedBy != null && submittedBy.equals(actor.userId())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "审批人不能是提交人：核销是终审动作，不能自己批自己");
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    String next = approved ? "APPROVED" : "REJECTED";
    sql.execute(
        "update finance_bad_debt_case set status=?, decided_by=?, decided_at=?::timestamptz, decision_comment=?,"
            + " updated_at=?::timestamptz where id = ?",
        next,
        actor.userId(),
        now,
        comment,
        now,
        id);
    writeEvent(
        actor.tenantId(),
        id,
        approved ? "APPROVED" : "REJECTED",
        status,
        next,
        comment,
        actor.userId(),
        now);
    return row(require(id));
  }

  // ================================================================ 核销执行

  /**
   * 执行核销：**作废原应收单 + 为仍可收回的余额新建承接单**。
   *
   * <p>⚠️ 核销路径只有这一条，且**一个字节都不写 {@code finance_receivable_line.settled_amount}** ——
   * 那一列的写者恒为 12.2.1.2 核销（V338 注释原文）。
   *
   * <p>为什么「作废 + 新单」是对的：坏账核销的实质是**把收不回来的金额从应收账上移走**。
   * 作废把整张单移出账簿，承接单把**仍然收得回**的那部分以一张干净的新单继续挂着 ——
   * 两步做完，应收账上减少的正好是核销金额，不会多也不会少。
   *
   * <p>⚠️ 「作废」与「新单」都走 **12.1.1.1 自己的能力**（它的冻结守卫、它的留痕、它的政策校验），
   * 本模块不自己往 12.1.1.1 的表里插一行。
   */
  @Transactional
  public FinanceBadDebtRow writeOff(UUID id, AccessService.Access actor) {
    var head = require(id);
    var status = head.get("status", String.class);
    if (!"APPROVED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "只有已批准待核销的申请可以执行核销（当前 " + statusLabel(status) + "）");
    }

    // 目标应收：以**当前**的账为准重新校验（申请时是快照，核销时是当下）。
    var target = receivableOf(head.get("receivable_id", UUID.class));
    var targetStatus = target.get("status", String.class);
    if (!"OPEN".equals(targetStatus)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "目标应收单当前状态是 " + statusLabel(targetStatus) + "，只有未核销（OPEN）的应收可以核销");
    }
    BigDecimal settled = target.get("settled_amount", BigDecimal.class);
    if (settled.signum() > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "目标应收单已回款 "
              + settled.toPlainString()
              + "：冲减「已核销」那部分属 12.2.1.2 核销的冲销（红冲）路径，"
              + "本模块只核销从未收到的部分，且不写 settled_amount（那一列的写者恒为 12.2.1.2）");
    }
    if (!target.get("currency", String.class).equals(head.get("currency", String.class))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标应收单的币种与申请时快照不一致，不可核销");
    }
    BigDecimal open = openBalance(target);
    BigDecimal writeOff = head.get("write_off_amount", BigDecimal.class);
    if (writeOff.compareTo(open) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "核销金额 "
              + writeOff.toPlainString()
              + " 超过该应收当前的未核销余额 "
              + open.toPlainString());
    }

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var caseNo = head.get("case_no", String.class);
    var reasonDetail = head.get("reason_detail", String.class);

    // ---- ① 作废原应收单（走 12.1.1.1 自己的逻辑：守卫 + 留痕） ----
    receivables.voidReceivable(
        target.get("id", UUID.class),
        new ReceivableModels.FinanceReceivableVoidInput(
            "坏账核销 " + caseNo + "：" + head.get("reason_type", String.class) + " " + reasonDetail),
        actor);

    // ---- ② 新单：承接仍可收回的余额 ----
    BigDecimal carry = open.subtract(writeOff);
    UUID replacementId = null;
    String replacementNo = "";
    boolean dueDateAdjusted = false;
    if (carry.signum() > 0) {
      var source = carryOverSource();
      var dueDate = target.get("due_date", LocalDate.class);
      var today = LocalDate.now(ZoneOffset.UTC);
      if (dueDate == null || dueDate.isBefore(today)) {
        // 12.1.1.1 的建单端点不允许到期日早于确认日 ⇒ 原单已逾期时承接单到期日取今天。
        dueDate = today;
        dueDateAdjusted = true;
      }
      var note =
          "由坏账核销 "
              + caseNo
              + " 承接（原单 "
              + target.get("receivable_no", String.class)
              + "，核销 "
              + writeOff.toPlainString()
              + "，余额 "
              + carry.toPlainString()
              + "）"
              + (dueDateAdjusted ? "；原单已逾期，承接单到期日取今天" : "");
      var created =
          receivables.create(
              new ReceivableModels.FinanceReceivableInput(
                  source,
                  ReceivableModels.CounterpartyType.valueOf(target.get("counterparty_type", String.class)),
                  target.get("counterparty_ref", UUID.class),
                  target.get("counterparty_name", String.class),
                  target.get("shop_id", UUID.class),
                  target.get("shop_name", String.class),
                  target.get("currency", String.class),
                  null,
                  null,
                  dueDate,
                  note,
                  List.of(
                      new ReceivableModels.FinanceReceivableLineInput(
                          "坏账核销承接余额", null, null, caseNo, null, null, carry))),
              actor);
      replacementId = created.id();
      replacementNo = created.receivableNo();
    }

    // ---- ③ 落核销结果（会计处理仍然不可用：凭证号恒为空串） ----
    sql.execute(
        "update finance_bad_debt_case set status='WRITTEN_OFF', write_off_at=?::timestamptz,"
            + " write_off_method='VOID_AND_REPLACE', voided_receivable_no=?, replacement_receivable_id=?,"
            + " replacement_receivable_no=?, replacement_due_date_adjusted=?, updated_at=?::timestamptz"
            + " where id = ?",
        now,
        target.get("receivable_no", String.class),
        replacementId,
        replacementNo,
        dueDateAdjusted,
        now,
        id);

    var trail =
        "作废原应收单 "
            + target.get("receivable_no", String.class)
            + "，核销 "
            + writeOff.toPlainString()
            + " "
            + target.get("currency", String.class)
            + (replacementNo.isEmpty() ? "，无承接余额" : "，承接单 " + replacementNo);
    writeEvent(actor.tenantId(), id, "WRITTEN_OFF", status, "WRITTEN_OFF", trail, actor.userId(), now);
    writeEvent(
        actor.tenantId(),
        id,
        "ACCOUNTING_PENDING",
        status,
        "WRITTEN_OFF",
        "会计处理不可用：归属模块 12.5.2.1（凭证管理未交付），本单不产生凭证",
        actor.userId(),
        now);
    return row(require(id));
  }

  /**
   * 承接单的应收来源：优先「其他」，退回「人工录入」。
   *
   * <p>⚠️ 「什么业务确认为应收」由租户决定（12.1.1.1 的应收确认政策）——
   * 系统**不替用户默认一个**。两个来源都没启用时，明确报错而不是默默造一张单。
   */
  private ReceivableModels.ReceivableSource carryOverSource() {
    var enabled =
        sql.fetch(
                "select source from finance_receivable_policy where enabled = true"
                    + " and source in ('OTHER','MANUAL') order by case source when 'OTHER' then 0 else 1 end")
            .map(r -> r.get(0, String.class));
    for (var s : enabled) {
      if ("OTHER".equals(s)) return ReceivableModels.ReceivableSource.OTHER;
    }
    for (var s : enabled) {
      if ("MANUAL".equals(s)) return ReceivableModels.ReceivableSource.MANUAL;
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "核销后仍有可收回余额需要新建承接单，但「其他」与「人工录入」两个应收来源的确认政策都未启用："
            + "请先在 12.1.1.1 的「应收政策」里启用其中一个（系统不替用户默认会计政策）");
  }

  // ================================================================ 作废

  /** 作废申请（理由必填）。终态不可作废。 */
  @Transactional
  public FinanceBadDebtRow voidCase(UUID id, FinanceBadDebtVoidInput input, AccessService.Access actor) {
    var head = require(id);
    var status = head.get("status", String.class);
    if (BadDebtStatus.valueOf(status).terminal()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "申请已是终态（" + statusLabel(status) + "），不可再作废");
    }
    String reason = input == null ? "" : trim(input.reason());
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "作废必须写明理由");
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_bad_debt_case set status='VOID', updated_at=?::timestamptz where id = ?", now, id);
    writeEvent(actor.tenantId(), id, "VOIDED", status, "VOID", reason, actor.userId(), now);
    return row(require(id));
  }

  // ================================================================ 内部

  /** 取一行（RLS 保证只看得到本租户的行）。 */
  private org.jooq.Record require(UUID id) {
    var row = sql.fetchOne("select * from finance_bad_debt_case where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "坏账核销申请 " + id + " 不存在或无权操作");
    }
    return row;
  }

  /** 目标应收单（**只读**）。 */
  private org.jooq.Record receivableOf(UUID id) {
    var row = sql.fetchOne("select * from finance_receivable where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "应收单 " + id + " 不存在或无权操作");
    }
    return row;
  }

  /** 未核销余额 = 总额 − 已核销额。 */
  private BigDecimal openBalance(org.jooq.Record receivable) {
    return receivable
        .get("amount", BigDecimal.class)
        .subtract(receivable.get("settled_amount", BigDecimal.class));
  }

  /** 写一条审计留痕（append-only）。 */
  private void writeEvent(
      UUID tenantId,
      UUID caseId,
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      UUID actorId,
      OffsetDateTime at) {
    sql.execute(
        "insert into finance_bad_debt_event(id,tenant_id,case_id,event_type,from_status,to_status,"
            + " reason,actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        caseId,
        eventType,
        fromStatus == null ? "" : fromStatus,
        toStatus == null ? "" : toStatus,
        reason == null ? "" : reason,
        actorId,
        at);
  }

  // ================================================================ 行映射

  private FinanceBadDebtRow row(org.jooq.Record r) {
    return new FinanceBadDebtRow(
        r.get("id", UUID.class),
        r.get("case_no", String.class),
        r.get("receivable_id", UUID.class),
        r.get("receivable_no", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_name", String.class),
        r.get("counterparty_ref", UUID.class),
        r.get("counterparty_name", String.class),
        r.get("currency", String.class),
        money(r.get("receivable_amount", BigDecimal.class)),
        money(r.get("receivable_settled", BigDecimal.class)),
        money(r.get("open_balance", BigDecimal.class)),
        money(r.get("write_off_amount", BigDecimal.class)),
        BadDebtReasonType.valueOf(r.get("reason_type", String.class)),
        r.get("reason_detail", String.class),
        r.get("dispute_id", UUID.class),
        r.get("dispute_no", String.class),
        r.get("dispute_status", String.class),
        money(r.get("dispute_amount", BigDecimal.class)),
        r.get("dispute_currency", String.class),
        BadDebtStatus.valueOf(r.get("status", String.class)),
        r.get("submitted_by", UUID.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("decided_by", UUID.class),
        r.get("decided_at", OffsetDateTime.class),
        r.get("decision_comment", String.class),
        r.get("write_off_at", OffsetDateTime.class),
        r.get("write_off_method", String.class) == null || r.get("write_off_method", String.class).isEmpty()
            ? null
            : BadDebtWriteOffMethod.valueOf(r.get("write_off_method", String.class)),
        r.get("voided_receivable_no", String.class),
        r.get("replacement_receivable_id", UUID.class),
        r.get("replacement_receivable_no", String.class),
        Boolean.TRUE.equals(r.get("replacement_due_date_adjusted", Boolean.class)),
        r.get("accounting_status", String.class),
        r.get("accounting_owner_module", String.class),
        r.get("accounting_ref", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceBadDebtEvidenceRow evidenceRow(org.jooq.Record r) {
    return new FinanceBadDebtEvidenceRow(
        r.get("id", UUID.class),
        r.get("case_id", UUID.class),
        r.get("evidence_no", Integer.class),
        BadDebtEvidenceType.valueOf(r.get("evidence_type", String.class)),
        r.get("title", String.class),
        r.get("reference_no", String.class),
        r.get("content", String.class),
        r.get("attachment_ref", String.class),
        r.get("occurred_on", LocalDate.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private FinanceBadDebtEventRow eventRow(org.jooq.Record r) {
    return new FinanceBadDebtEventRow(
        r.get("id", UUID.class),
        r.get("case_id", UUID.class),
        r.get("event_type", String.class),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("reason", String.class),
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 小工具

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static String nextReference(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  private static String statusLabel(String status) {
    try {
      return BadDebtStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  /**
   * 拼绑定参数。
   *
   * <p>⚠️ **必须返回 {@code Object[]} 而不是 {@code List}**：jOOQ 不会把「作为唯一 vararg 传进来的
   * Collection」展开成多个绑定 —— 它会把这个 List 当成一个参数去绑定，然后报
   * 「Type class java.util.ArrayList is not supported in dialect POSTGRES」。
   * （12.1.1.2 的 {@code ReceivableAgingService.concat} 是同一个坑的同款写法。）
   */
  private static Object[] concat(List<Object> base, Object... extra) {
    var out = new Object[base.size() + extra.length];
    for (int i = 0; i < base.size(); i++) out[i] = base.get(i);
    System.arraycopy(extra, 0, out, base.size(), extra.length);
    return out;
  }
}
