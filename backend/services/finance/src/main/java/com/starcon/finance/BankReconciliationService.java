package com.starcon.finance;

import static com.starcon.finance.BankReconciliationModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.7.1.2 银行对账。
 *
 * <p>蓝图 12-finance.md：「导入银行/支付平台流水与系统记录自动匹配，输出未匹配项，支持人工认领与说明，生成对账报告。」输入：「流水文件（导入）；系统收支记录；匹配规则。」输出：「匹配结果 + 未匹配清单 + 对账报告。」关系：「上游 = 12.7.1.1、12.2；下游 = 12.3.2.1 对账。<b>未匹配项要能认领到具体单据</b>，否则余额永远平不了——这是财务日常最高频的工作。」
 *
 * <p><b>本模块的四项能力，各自的真数据源</b>：
 *
 * <ol>
 *   <li><b>流水导入</b> —— CSV / JSON 真解析，落 {@code finance_bank_recon_statement_line}
 *       （外部事实，append-only，幂等键 = 批次 + 银行流水号）。</li>
 *   <li><b>自动匹配</b> —— 系统侧候选集来自**同服务的真实上游表**
 *       （12.2.1.1 收款单 / 12.2.2.1 付款单 / 12.3.1.2 结算明细行），
 *       快照进 {@code finance_bank_recon_system_line}；匹配规则来自
 *       {@code finance_bank_recon_rule}（**租户自己维护**，不是写死的常量）。</li>
 *   <li><b>未匹配清单 + 人工认领</b> —— {@code finance_bank_recon_unmatched}（当前态）
 *       + {@code finance_bank_recon_claim}（append-only 举证）。</li>
 *   <li><b>对账报告</b> —— {@code finance_bank_recon_report}（含余额调节表）。</li>
 * </ol>
 *
 * <p><b>五条口径纪律</b>（每一条都有实现，不是口号）：
 *
 * <ol>
 *   <li><b>不允许「金额相同即匹配」（卷 12 硬约束）。</b>三种规则类型都**至少组合两个
 *       独立维度**（单号 / 对手方 / 日期窗口）+ 方向 + 金额；<b>没有任何一条只看金额</b>。
 *       日期窗口上限 31 天（DB CHECK 钉死）—— 把窗口放到几百天等于用「金额相同」冒充匹配。</li>
 *   <li><b>未匹配项要能认领到具体单据。</b>认领目标是**受控词表 + 单据 id + 单号**，
 *       DB 层校验该单据**真的存在于本租户**；认领**只写本模块的表**，
 *       <b>不改</b>被认领单据的状态（那是上游模块的写入所有权）。</li>
 *   <li><b>匹配是增量的，不撤销。</b>银行流水是外部事实（append-only），
 *       已匹配的流水行被钉住；重跑匹配只**补**匹配，不会抹掉已经认领过的差异。
 *       要重来 ⇒ 新建一个对账批次。</li>
 *   <li><b>一次对账只有一个币种。</b>币种继承 12.7.1.1 的账户 ⇒ 全部金额可相加，
 *       本模块<b>不</b>做任何跨币种折算。</li>
 *   <li><b>读不到 ≠ 没有。</b>我方账面余额读不到时，调节后余额与差额**恒为 null**
 *       （写 0 会被读成「账是平的」）。</li>
 * </ol>
 */
@Service
public class BankReconciliationService {

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 默认页长。 */
  static final int DEFAULT_PAGE_SIZE = 20;

  /** 对账单号前缀。 */
  static final String RECON_NO_PREFIX = "BR-";

  /** 导入告警最多回传多少条（超出部分只给总数，避免一次导入刷爆响应）。 */
  static final int MAX_WARNINGS = 50;

  /** 内置默认规则（租户第一次建批次时写进规则表，之后租户可改）。 */
  static final List<DefaultRule> DEFAULT_RULES =
      List.of(
          new DefaultRule(
              "REF_EXACT",
              "单号精确匹配",
              FinanceBankReconRuleType.REFERENCE_EXACT,
              10,
              3,
              BigDecimal.ZERO,
              false,
              "对方单号（订单号 / 收付款单号）完全相等 + 金额相等 + 方向相同 + 日期在 3 天窗口内。"),
          new DefaultRule(
              "CP_AMOUNT",
              "对手方与金额匹配",
              FinanceBankReconRuleType.COUNTERPARTY_AMOUNT,
              20,
              3,
              BigDecimal.ZERO,
              false,
              "对手方名称规范化后相等 + 金额相等 + 方向相同 + 日期在 3 天窗口内。"),
          new DefaultRule(
              "AMT_WINDOW",
              "金额与日期窗口匹配",
              FinanceBankReconRuleType.AMOUNT_DATE_WINDOW,
              30,
              2,
              BigDecimal.ZERO,
              false,
              "金额相等 + 方向相同 + 日期在 2 天窗口内 + 对手方不矛盾（两边都有名字却不一致 ⇒ 不是候选）。"));

  /** 内置规则的一条定义。 */
  record DefaultRule(
      String code,
      String name,
      FinanceBankReconRuleType type,
      int priority,
      int windowDays,
      BigDecimal tolerance,
      boolean counterpartyRequired,
      String note) {}

  private final DSLContext sql;
  private final ObjectMapper json;

  public BankReconciliationService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 必须把「本模块没接」与「上游没交付」分开写。
   */
  public FinanceBankReconCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceBankReconCapability>();
    var gaps = new ArrayList<FinanceBankReconDownstreamGap>();

    capabilities.add(
        new FinanceBankReconCapability(
            "12.7.1.2",
            "银行/支付平台流水导入",
            true,
            "finance_bank_recon_statement_line（本模块自己的表）",
            "",
            "CSV / JSON **真解析**（不是把原文存下来就算导入）；"
                + "流水行是**外部事实 ⇒ append-only**（DB 触发器 + 收回运行角色权限两层钉死）；"
                + "幂等键 = 对账批次 + 银行流水号（重复导入不重复入账）；"
                + "时间必须带时区偏移（`2026-10-01T08:30:00+08:00` 或 `...Z`）——"
                + "没有偏移的时间串直接拒收（东八区偏 8 小时就是错账）。"));

    capabilities.add(
        new FinanceBankReconCapability(
            "12.7.1.2",
            "与系统记录的自动匹配",
            true,
            "finance_bank_recon_match + finance_bank_recon_rule",
            "",
            "系统侧候选集来自**同服务的真实上游表**：12.2.1.1 收款单 / 12.2.2.1 付款单 / "
                + "12.3.1.2 结算明细行（同服务同库 ⇒ 合法读路径），快照进 "
                + "`finance_bank_recon_system_line`；"
                + "🔴 **不允许「金额相同即匹配」（卷 12 硬约束）**：三种规则类型"
                + "（单号精确 / 对手方+金额 / 金额+日期窗口）都**至少组合两个独立维度**"
                + "+ 方向 + 金额，**没有任何一条只看金额**；"
                + "日期窗口上限 31 天（DB CHECK 钉死）。"));

    capabilities.add(
        new FinanceBankReconCapability(
            "12.7.1.2",
            "未匹配清单与人工认领",
            true,
            "finance_bank_recon_unmatched + finance_bank_recon_claim",
            "",
            "🔴 蓝图加粗句「未匹配项要能认领到具体单据」落成**数据层的一等公民**："
                + "认领目标是受控词表（收款单 / 付款单 / 结算单 / 其它）+ 单据 id + 单号，"
                + "DB 层校验该单据**真的存在于本租户**（认领不存在的单据直接被拒）；"
                + "每次认领 / 排除都**追加**一条 `finance_bank_recon_claim`（append-only 举证），"
                + "可以多次认领、留痕不改写；"
                + "⚠️ 认领**只写本模块的表**，**不改**被认领单据的状态（那是上游的写入所有权）。"));

    capabilities.add(
        new FinanceBankReconCapability(
            "12.7.1.2",
            "对账报告（余额调节表）",
            true,
            "finance_bank_recon_report（本模块自己的表）",
            "",
            "报告 = 笔数 + 金额 + **余额调节表**："
                + "调节后余额 = 银行期末余额 + 银行已收未入账 − 银行已付未入账 "
                + "− 我方已收银行未收 + 我方已付银行未付；"
                + "差额 = 调节后余额 − 我方账面余额；"
                + "我方账面余额取自 12.7.1.1 的余额历史（**读不到就是 null**，"
                + "此时调节后余额与差额也恒为 null，报告如实标 `bookBalanceAvailable=false`，"
                + "写 0 会被读成「账是平的」）。"));

    capabilities.add(
        new FinanceBankReconCapability(
            "12.2",
            "收付款单的受管资金账户引用",
            false,
            "finance_receipt.fund_account_ref 由 V341 的 CHECK 钉死恒为 NULL；"
                + "finance_payment_order.payment_account_id 是无 FK 的自由 uuid",
            "12.2",
            "⇒ 系统侧候选集按**「同币种 + 期间」**取，并把「这条记录自己声明挂在哪个账户上」"
                + "作为**附加过滤**（声明了别的账户 ⇒ 排除）。"
                + "本模块**不 ALTER 上游的表**；上游把受管账户引用接线之后，"
                + "候选集**自动**收窄，不需要改本模块。"));

    capabilities.add(
        new FinanceBankReconCapability(
            "12.3.2.1",
            "结算单 ↔ 平台账对账",
            false,
            "下游模块未交付",
            "12.3.2.1",
            "⇒ 本模块**不**做 12.3.2.1 的核对（那是结算单与平台账之间的事），"
                + "只交付「银行/支付平台流水 ⇄ 我方收支记录」这一层的对账；"
                + "下游 12.3.2.1 拿本模块的未匹配清单与对账报告做它自己的核对。"));

    capabilities.add(
        new FinanceBankReconCapability(
            "12.7.2.1",
            "跨币种折算 / 汇率敞口",
            false,
            "本模块不读汇率：汇率归 catalog 5.2.2.1，敞口测算归 12.7.2.1",
            "12.7.2.1",
            "⇒ 一次对账只有一个币种（继承 12.7.1.1 的账户），"
                + "本模块**绝不做**跨币种折算，也**不**按 1:1 假装折算。"));

    gaps.add(
        new FinanceBankReconDownstreamGap(
            "12.3.2.1", "对账（结算单侧）", "finance", false, "12.3 结算与对账",
            "下游未交付：它拿本模块的未匹配清单与报告做结算单侧的核对，"
                + "本模块**不代**它产出结算单侧的差异。"));

    gaps.add(
        new FinanceBankReconDownstreamGap(
            "12.2", "收付款单引用受管资金账户", "finance", false, "12 财务核算",
            "上游未接线：候选集按「同币种 + 期间」取（见上面能力声明），"
                + "上游放开那条 CHECK 之后候选集自动收窄。"));

    gaps.add(
        new FinanceBankReconDownstreamGap(
            "12.3.2.1", "自动对账任务的调度", "finance", false, "12.3 结算与对账",
            "本模块的匹配是**手动触发**的端点动作；定时跑批的调度归下游模块，"
                + "本模块**不**自带定时器。"));

    return new FinanceBankReconCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：词表 + **真实数据**（资金账户取自 12.7.1.1，规则取自规则表）。 */
  public FinanceBankReconOptions options() {
    var accounts =
        sql.fetch(
                "select id,account_no,account_name,account_type,currency,legal_entity_name,"
                    + " current_balance,current_balance_at from finance_fund_account"
                    + " where status = 'ACTIVE' order by account_no")
            .map(
                r ->
                    new FinanceBankReconAccount(
                        r.get("id", UUID.class),
                        r.get("account_no", String.class),
                        r.get("account_name", String.class),
                        r.get("account_type", String.class),
                        r.get("account_type", String.class),
                        r.get("currency", String.class),
                        r.get("legal_entity_name", String.class),
                        r.get("current_balance", BigDecimal.class),
                        r.get("current_balance_at", OffsetDateTime.class)));

    var rules =
        sql.fetch(
                "select rule_code,rule_name,rule_type,priority,enabled,date_window_days,"
                    + " amount_tolerance,counterparty_required,builtin"
                    + " from finance_bank_recon_rule order by priority,rule_code")
            .map(
                r ->
                    new FinanceBankReconRuleRow(
                        r.get("id", UUID.class),
                        r.get("rule_code", String.class),
                        r.get("rule_name", String.class),
                        r.get("rule_type", String.class),
                        FinanceBankReconRuleType.valueOf(r.get("rule_type", String.class)).label(),
                        r.get("priority", Integer.class),
                        r.get("enabled", Boolean.class),
                        r.get("date_window_days", Integer.class),
                        r.get("amount_tolerance", BigDecimal.class),
                        r.get("counterparty_required", Boolean.class),
                        r.get("builtin", Boolean.class),
                        "",
                        r.get("created_at", OffsetDateTime.class),
                        r.get("updated_at", OffsetDateTime.class)));

    var currencies =
        sql.fetch("select distinct currency from finance_fund_account order by currency")
            .map(r -> r.get(0, String.class));

    return new FinanceBankReconOptions(
        List.of(
            new FinanceBankReconOption(
                FinanceBankReconDirection.IN.name(), FinanceBankReconDirection.IN.label()),
            new FinanceBankReconOption(
                FinanceBankReconDirection.OUT.name(), FinanceBankReconDirection.OUT.label())),
        List.of(
            new FinanceBankReconOption(
                FinanceBankReconRuleType.REFERENCE_EXACT.name(),
                FinanceBankReconRuleType.REFERENCE_EXACT.label()),
            new FinanceBankReconOption(
                FinanceBankReconRuleType.COUNTERPARTY_AMOUNT.name(),
                FinanceBankReconRuleType.COUNTERPARTY_AMOUNT.label()),
            new FinanceBankReconOption(
                FinanceBankReconRuleType.AMOUNT_DATE_WINDOW.name(),
                FinanceBankReconRuleType.AMOUNT_DATE_WINDOW.label())),
        List.of(
            new FinanceBankReconOption(
                FinanceBankReconSourceType.RECEIPT.name(),
                FinanceBankReconSourceType.RECEIPT.label()),
            new FinanceBankReconOption(
                FinanceBankReconSourceType.PAYMENT_ORDER.name(),
                FinanceBankReconSourceType.PAYMENT_ORDER.label()),
            new FinanceBankReconOption(
                FinanceBankReconSourceType.SETTLEMENT_LINE.name(),
                FinanceBankReconSourceType.SETTLEMENT_LINE.label())),
        List.of(
            new FinanceBankReconOption(
                FinanceBankReconUnmatchedStatus.OPEN.name(),
                FinanceBankReconUnmatchedStatus.OPEN.label()),
            new FinanceBankReconOption(
                FinanceBankReconUnmatchedStatus.CLAIMED.name(),
                FinanceBankReconUnmatchedStatus.CLAIMED.label()),
            new FinanceBankReconOption(
                FinanceBankReconUnmatchedStatus.EXCLUDED.name(),
                FinanceBankReconUnmatchedStatus.EXCLUDED.label())),
        List.of(
            new FinanceBankReconOption(
                FinanceBankReconDocumentType.RECEIPT.name(),
                FinanceBankReconDocumentType.RECEIPT.label()),
            new FinanceBankReconOption(
                FinanceBankReconDocumentType.PAYMENT_ORDER.name(),
                FinanceBankReconDocumentType.PAYMENT_ORDER.label()),
            new FinanceBankReconOption(
                FinanceBankReconDocumentType.SETTLEMENT.name(),
                FinanceBankReconDocumentType.SETTLEMENT.label()),
            new FinanceBankReconOption(
                FinanceBankReconDocumentType.OTHER.name(),
                FinanceBankReconDocumentType.OTHER.label())),
        accounts,
        currencies,
        "账户候选取自 **12.7.1.1 的真实账户档案**（不是常量）；"
            + "规则候选取自**本租户自己的规则表**（表里不预置任何一行 —— "
            + "第一次建对账批次时服务层把内置默认规则写进去，之后租户可改）；"
            + "方向 / 规则类型 / 来源类型 / 认领单据类型都是**受控词表**。");
  }

  // ================================================================ 对账批次

  /** 对账批次列表（真分页 + 真筛选 + 真排序）。 */
  public FinanceBankReconSessionPage listSessions(
      UUID accountId, String status, String keyword, int page, int size) {
    int pageNo = Math.max(0, page);
    int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();

    if (accountId != null) {
      where.append(" and account_id = ?");
      args.add(accountId);
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      args.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      var kw = keyword.trim().toLowerCase(Locale.ROOT);
      where.append(
          " and (position(? in lower(recon_no)) > 0"
              + " or position(? in lower(account_no)) > 0"
              + " or position(? in lower(account_name)) > 0"
              + " or position(? in lower(note)) > 0)");
      args.add(kw);
      args.add(kw);
      args.add(kw);
      args.add(kw);
    }

    var total =
        sql.fetchOne("select count(*) from finance_bank_recon_session" + where, args.toArray())
            .get(0, Long.class);

    var bindings = new ArrayList<Object>(args);
    bindings.add(pageSize);
    bindings.add(pageNo * pageSize);
    var rows =
        sql.fetch(
            "select id,recon_no,account_id,account_no,account_name,currency,period_start,"
                + " period_end,statement_opening_balance,statement_closing_balance,"
                + " book_opening_balance,book_closing_balance,status,source_system,"
                + " statement_line_count,system_line_count,matched_count,unmatched_bank_count,"
                + " unmatched_system_count,reconciled_by,reconciled_at,note,version,created_at,"
                + " updated_at from finance_bank_recon_session"
                + where
                + " order by period_end desc, recon_no desc, id asc limit ? offset ?",
            bindings.toArray());

    return new FinanceBankReconSessionPage(
        rows.map(BankReconciliationService::sessionRow), pageNo, pageSize, total);
  }

  /** 对账批次详情。 */
  public FinanceBankReconSessionRow getSession(UUID id) {
    return sessionRow(requireSession(id));
  }

  /**
   * 建对账批次。
   *
   * <p>⚠️ 三道闸：① 账户必须存在且**启用中**（停用账户不再对账）；
   * ② 币种**继承账户**（单账户单币种，本模块不折算）；③ 期间必须合法（结束 ≥ 开始）。
   */
  @Transactional
  public FinanceBankReconSessionRow createSession(
      FinanceBankReconSessionCreateInput input, AccessService.Access actor) {
    var account =
        sql.fetchOne(
            "select id,account_no,account_name,account_type,currency,status"
                + " from finance_fund_account where id = ?",
            input.accountId());
    if (account == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "资金账户不存在：" + input.accountId());
    }
    if (!"ACTIVE".equals(account.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "资金账户已停用，不能对账：" + account.get("account_no", String.class));
    }
    if (input.periodEnd().isBefore(input.periodStart())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "对账期间的结束日不能早于开始日");
    }

    var id = UUID.randomUUID();
    var now = nowUtc();
    var reconNo = generatedReconNo();
    var currency = account.get("currency", String.class);

    // 规则表**不预置任何一行** ⇒ 租户第一次建批次时把内置默认规则写进去。
    seedDefaultRulesIfEmpty(actor.tenantId());

    sql.execute(
        "insert into finance_bank_recon_session(id,tenant_id,recon_no,account_id,account_no,"
            + " account_name,currency,period_start,period_end,statement_opening_balance,"
            + " statement_closing_balance,book_opening_balance,book_closing_balance,source_system,"
            + " status,statement_line_count,system_line_count,matched_count,unmatched_bank_count,"
            + " unmatched_system_count,reconciled_by,reconciled_at,note,version,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',0,0,0,0,0,null,null,?,0,?::timestamptz,"
            + " ?::timestamptz)",
        id,
        actor.tenantId(),
        reconNo,
        account.get("id", UUID.class),
        account.get("account_no", String.class),
        account.get("account_name", String.class),
        currency,
        input.periodStart(),
        input.periodEnd(),
        money(input.statementOpeningBalance()),
        money(input.statementClosingBalance()),
        bookBalanceAt(actor.tenantId(), account.get("id", UUID.class), input.periodStart(), true),
        bookBalanceAt(actor.tenantId(), account.get("id", UUID.class), input.periodEnd(), false),
        trim(input.sourceSystem()).isEmpty() ? "manual" : trim(input.sourceSystem()),
        trim(input.note()),
        now,
        now);

    appendEvent(
        actor.tenantId(),
        id,
        "SESSION_CREATED",
        "建立对账批次 " + reconNo + "（账户 " + account.get("account_no", String.class) + "）",
        Map.of("reconNo", reconNo),
        actor.userId(),
        now);

    return sessionRow(requireSession(id));
  }

  // ================================================================ 流水导入

  /**
   * 导入银行 / 支付平台流水。
   *
   * <p>⚠️ 逐行解析：任何一行不合法都**不静默丢弃** —— 它进 {@code warnings}
   * 并带上行号与原因。人看到「第 7 行被跳过：时间必须带时区偏移」才知道发生了什么。
   */
  @Transactional
  public FinanceBankReconImportResult importStatement(
      UUID sessionId, FinanceBankReconImportInput input, AccessService.Access actor) {
    var session = requireSession(sessionId);
    var status = session.get("status", String.class);
    if ("RECONCILED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "对账批次已确认，不能再导入流水（要重来请新建一个批次）");
    }
    var currency = session.get("currency", String.class);

    var parsed =
        input.format() == FinanceBankReconImportFormat.JSON
            ? parseJson(input.content())
            : parseCsv(input.content());

    var warnings = new ArrayList<String>();
    var imported = 0;
    var skipped = 0;
    var batch = "IMP-" + UUID.randomUUID().toString().substring(0, 8);

    for (var row : parsed) {
      var index = row.index();
      OffsetDateTime occurredAt;
      try {
        occurredAt = parseOccurredAt(row.value("occurredat"));
      } catch (IllegalArgumentException e) {
        skipped++;
        addWarning(warnings, index, e.getMessage());
        continue;
      }

      FinanceBankReconDirection direction;
      try {
        direction = parseDirection(row.value("direction"));
      } catch (IllegalArgumentException e) {
        skipped++;
        addWarning(warnings, index, e.getMessage());
        continue;
      }

      BigDecimal amount;
      try {
        amount = new BigDecimal(row.value("amount").trim());
        if (amount.signum() <= 0) {
          throw new IllegalArgumentException(
              "金额必须为正（支出用 direction=OUT 表达，金额不写负号）");
        }
        amount = money(amount);
      } catch (NumberFormatException | ArithmeticException e) {
        skipped++;
        addWarning(warnings, index, "金额不是合法数字：" + row.value("amount"));
        continue;
      } catch (IllegalArgumentException e) {
        skipped++;
        addWarning(warnings, index, e.getMessage());
        continue;
      }

      var rowCurrency = row.value("currency").trim().toUpperCase(Locale.ROOT);
      if (!rowCurrency.matches("^[A-Z]{3}$")) {
        skipped++;
        addWarning(warnings, index, "币种必须是 3 位大写字母：" + row.value("currency"));
        continue;
      }
      if (!rowCurrency.equals(currency)) {
        skipped++;
        addWarning(
            warnings,
            index,
            "币种 " + rowCurrency + " 与对账批次币种 " + currency + " 不一致，已跳过");
        continue;
      }

      var bankReference = row.value("bankreference").trim();
      if (bankReference.isEmpty()) {
        skipped++;
        addWarning(warnings, index, "银行流水号不能为空");
        continue;
      }

      var exists =
          sql.fetchOne(
                  "select 1 from finance_bank_recon_statement_line"
                      + " where session_id = ? and bank_reference = ?",
                  sessionId,
                  bankReference)
              != null;
      if (exists) {
        skipped++;
        addWarning(warnings, index, "银行流水号 " + bankReference + " 已存在，幂等跳过");
        continue;
      }

      sql.execute(
          "insert into finance_bank_recon_statement_line(id,tenant_id,session_id,occurred_at,"
              + " direction,amount,currency,bank_reference,external_reference,counterparty,memo,"
              + " import_batch,imported_by,created_at)"
              + " values (?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          actor.tenantId(),
          sessionId,
          occurredAt,
          direction.name(),
          amount,
          currency,
          bankReference,
          row.value("externalreference").trim(),
          row.value("counterparty").trim(),
          row.value("memo").trim(),
          batch,
          actor.userId(),
          nowUtc());
      imported++;
    }

    // 对账单余额：本次导入给了就更新（没给 = 不改）。
    if (input.statementOpeningBalance() != null || input.statementClosingBalance() != null) {
      sql.execute(
          "update finance_bank_recon_session set"
              + " statement_opening_balance = coalesce(?,statement_opening_balance),"
              + " statement_closing_balance = coalesce(?,statement_closing_balance),"
              + " version = version + 1, updated_at = ?::timestamptz where id = ? and tenant_id = ?",
          input.statementOpeningBalance() == null
              ? null
              : money(input.statementOpeningBalance()),
          input.statementClosingBalance() == null
              ? null
              : money(input.statementClosingBalance()),
          nowUtc(),
          sessionId,
          actor.tenantId());
    }

    var lineCount = countStatementLines(sessionId);
    // ⚠️ 只在「还没导入过」时把状态推进到 IMPORTED。
    //   已 MATCHED 的批次再导入新流水**不回退状态**（DB 触发器也禁止回退），
    //   匹配数等计数会在下一次跑匹配时刷新 —— 显示陈旧计数好过悄悄改口径。
    if ("DRAFT".equals(status)) {
      sql.execute(
          "update finance_bank_recon_session set status = 'IMPORTED', statement_line_count = ?,"
              + " version = version + 1, updated_at = ?::timestamptz where id = ? and tenant_id = ?",
          lineCount,
          nowUtc(),
          sessionId,
          actor.tenantId());
    } else {
      sql.execute(
          "update finance_bank_recon_session set statement_line_count = ?, version = version + 1,"
              + " updated_at = ?::timestamptz where id = ? and tenant_id = ?",
          lineCount,
          nowUtc(),
          sessionId,
          actor.tenantId());
    }

    appendEvent(
        actor.tenantId(),
        sessionId,
        "STATEMENT_IMPORTED",
        "导入流水：入账 " + imported + " 行，跳过 " + skipped + " 行",
        Map.of(
            "imported", imported,
            "skipped", skipped,
            "parsed", parsed.size(),
            "batch", batch),
        actor.userId(),
        nowUtc());

    return new FinanceBankReconImportResult(
        sessionId,
        session.get("recon_no", String.class),
        parsed.size(),
        imported,
        skipped,
        lineCount,
        List.copyOf(warnings));
  }

  // ================================================================ 自动匹配

  /**
   * 跑自动匹配。
   *
   * <p><b>增量语义</b>：只给**还没匹配上**的银行流水行找系统记录；
   * 已经匹配上的行被钉住（银行流水是外部事实，匹配结果也不回退）。
   * 匹配上 ⇒ 该行原来的**待处理**未匹配项被移除。
   */
  @Transactional
  public FinanceBankReconMatchResult runMatch(UUID sessionId, AccessService.Access actor) {
    var session = requireSession(sessionId);
    if ("RECONCILED".equals(session.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "对账批次已确认，不能再跑匹配（要重来请新建一个批次）");
    }
    var currency = session.get("currency", String.class);
    var accountId = session.get("account_id", UUID.class);
    var periodStart = session.get("period_start", LocalDate.class);
    var periodEnd = session.get("period_end", LocalDate.class);

    // ---- 1. 系统侧候选集：从同服务的真实上游表取快照 ----
    var systemLineCount =
        refreshSystemLines(actor.tenantId(), sessionId, accountId, currency, periodStart, periodEnd);

    // ---- 2. 规则（租户自己维护的规则表；没有启用规则 ⇒ 什么都不匹配）----
    var rules = loadRules(actor.tenantId());

    // ---- 3. 逐条流水找候选 ----
    var statementLines =
        sql.fetch(
                "select id,occurred_at,direction,amount,currency,bank_reference,external_reference,"
                    + " counterparty from finance_bank_recon_statement_line"
                    + " where session_id = ? order by occurred_at, id",
                sessionId)
            .map(
                r ->
                    new Candidate(
                        r.get("id", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class),
                        r.get("direction", String.class),
                        r.get("amount", BigDecimal.class),
                        r.get("currency", String.class),
                        r.get("external_reference", String.class),
                        r.get("counterparty", String.class)));

    var alreadyMatched =
        sql.fetch("select statement_line_id, system_line_id from finance_bank_recon_match"
                + " where session_id = ?", sessionId)
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    r -> r.get("statement_line_id", UUID.class),
                    r -> r.get("system_line_id", UUID.class),
                    (a, b) -> a));

    var systemLines =
        sql.fetch(
                "select id,occurred_at,occurred_precision,direction,amount,currency,"
                    + " external_reference,counterparty,source_type,source_id"
                    + " from finance_bank_recon_system_line where session_id = ?"
                    + " order by occurred_at, id",
                sessionId)
            .map(
                r ->
                    new Candidate(
                        r.get("id", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class),
                        r.get("direction", String.class),
                        r.get("amount", BigDecimal.class),
                        r.get("currency", String.class),
                        r.get("external_reference", String.class),
                        r.get("counterparty", String.class)));

    var usedSystemLines = new HashSet<>(alreadyMatched.values());
    var matchedCount = alreadyMatched.size();
    var appliedRules = new ArrayList<String>();

    for (var statement : statementLines) {
      if (alreadyMatched.containsKey(statement.id())) continue;

      for (var rule : rules) {
        var best =
            systemLines.stream()
                .filter(s -> !usedSystemLines.contains(s.id()))
                .filter(s -> isMatchCandidate(statement, s, rule))
                .min(
                    Comparator
                        .comparingInt((Candidate s) -> dayDifference(statement.occurredAt(), s.occurredAt()))
                        .thenComparing(s -> s.id().toString()))
                .orElse(null);
        if (best == null) continue;

        var basis = matchBasis(statement, best, rule);
        var dayDiff = dayDifference(statement.occurredAt(), best.occurredAt());
        sql.execute(
            "insert into finance_bank_recon_match(id,tenant_id,session_id,statement_line_id,"
                + " system_line_id,rule_code,rule_type,match_basis,day_difference,matched_by,"
                + " matched_at,created_at)"
                + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            actor.tenantId(),
            sessionId,
            statement.id(),
            best.id(),
            rule.code(),
            rule.type().name(),
            basis,
            dayDiff,
            actor.userId(),
            nowUtc(),
            nowUtc());
        usedSystemLines.add(best.id());
        alreadyMatched.put(statement.id(), best.id());
        matchedCount++;
        if (!appliedRules.contains(rule.code())) appliedRules.add(rule.code());

        // 这行流水现在配上了 ⇒ 移除它的**待处理**未匹配项（已认领 / 已排除的不动）。
        sql.execute(
            "delete from finance_bank_recon_unmatched where session_id = ?"
                + " and statement_line_id = ? and status = 'OPEN'",
            sessionId,
            statement.id());
        sql.execute(
            "delete from finance_bank_recon_unmatched where session_id = ?"
                + " and system_line_id = ? and status = 'OPEN'",
            sessionId,
            best.id());
        break;
      }
    }

    // ---- 4. 生成 / 刷新未匹配清单 ----
    var openStatement =
        statementLines.stream()
            .filter(s -> !alreadyMatched.containsKey(s.id()))
            .map(Candidate::id)
            .collect(java.util.stream.Collectors.toSet());
    var openSystem =
        systemLines.stream()
            .filter(s -> !usedSystemLines.contains(s.id()))
            .map(Candidate::id)
            .collect(java.util.stream.Collectors.toSet());

    // ⚠️ 匹配是**增量**的：这里**不**删除任何未匹配项。
    //   一行流水刚配上时，它的「待处理」未匹配项已在上面的循环里删掉了；
    //   已认领 / 已排除的差异是**人工处理过的历史**，删掉就是毁掉举证。

    for (var statement : statementLines) {
      if (!openStatement.contains(statement.id())) continue;
      ensureUnmatchedRow(actor.tenantId(), sessionId, statement, "BANK");
    }
    for (var system : systemLines) {
      if (!openSystem.contains(system.id())) continue;
      ensureUnmatchedRow(actor.tenantId(), sessionId, system, "SYSTEM");
    }

    var unmatchedBank =
        sql.fetchOne(
                "select count(*) from finance_bank_recon_unmatched"
                    + " where session_id = ? and side = 'BANK' and status = 'OPEN'",
                sessionId)
            .get(0, Integer.class);
    var unmatchedSystem =
        sql.fetchOne(
                "select count(*) from finance_bank_recon_unmatched"
                    + " where session_id = ? and side = 'SYSTEM' and status = 'OPEN'",
                sessionId)
            .get(0, Integer.class);

    sql.execute(
        "update finance_bank_recon_session set status = 'MATCHED', system_line_count = ?,"
            + " matched_count = ?, unmatched_bank_count = ?, unmatched_system_count = ?,"
            + " version = version + 1, updated_at = ?::timestamptz where id = ? and tenant_id = ?",
        systemLineCount,
        matchedCount,
        unmatchedBank,
        unmatchedSystem,
        nowUtc(),
        sessionId,
        actor.tenantId());

    appendEvent(
        actor.tenantId(),
        sessionId,
        "MATCH_EXECUTED",
        "自动匹配：匹配 " + matchedCount + " 对，未匹配（银行侧 " + unmatchedBank
            + " / 系统侧 " + unmatchedSystem + "）",
        Map.of(
            "matched", matchedCount,
            "unmatchedBank", unmatchedBank,
            "unmatchedSystem", unmatchedSystem,
            "rules", appliedRules),
        actor.userId(),
        nowUtc());

    return new FinanceBankReconMatchResult(
        sessionId,
        session.get("recon_no", String.class),
        statementLines.size(),
        systemLineCount,
        matchedCount,
        unmatchedBank,
        unmatchedSystem,
        List.copyOf(appliedRules));
  }

  // ================================================================ 列表

  /** 银行流水行（真分页）。 */
  public FinanceBankReconStatementLinePage statementLines(UUID sessionId, int page, int size) {
    requireSession(sessionId);
    int pageNo = Math.max(0, page);
    int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    var total =
        sql.fetchOne(
                "select count(*) from finance_bank_recon_statement_line where session_id = ?",
                sessionId)
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select id,session_id,occurred_at,direction,amount,currency,bank_reference,"
                + " external_reference,counterparty,memo,import_batch,created_at"
                + " from finance_bank_recon_statement_line where session_id = ?"
                + " order by occurred_at, id limit ? offset ?",
            sessionId,
            pageSize,
            pageNo * pageSize);
    return new FinanceBankReconStatementLinePage(
        rows.map(BankReconciliationService::statementLineRow), pageNo, pageSize, total);
  }

  /** 系统记录快照（真分页）。 */
  public FinanceBankReconSystemLinePage systemLines(UUID sessionId, int page, int size) {
    requireSession(sessionId);
    int pageNo = Math.max(0, page);
    int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    var total =
        sql.fetchOne(
                "select count(*) from finance_bank_recon_system_line where session_id = ?", sessionId)
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select id,session_id,source_type,source_id,source_no,source_label,occurred_at,"
                + " occurred_precision,direction,amount,raw_amount,currency,external_reference,"
                + " counterparty,memo,created_at from finance_bank_recon_system_line"
                + " where session_id = ? order by occurred_at, id limit ? offset ?",
            sessionId,
            pageSize,
            pageNo * pageSize);
    return new FinanceBankReconSystemLinePage(
        rows.map(BankReconciliationService::systemLineRow), pageNo, pageSize, total);
  }

  /** 匹配结果（真分页）。 */
  public FinanceBankReconMatchPage matches(UUID sessionId, int page, int size) {
    requireSession(sessionId);
    int pageNo = Math.max(0, page);
    int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    var total =
        sql.fetchOne("select count(*) from finance_bank_recon_match where session_id = ?", sessionId)
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select m.id,m.session_id,m.statement_line_id,m.system_line_id,m.rule_code,m.rule_type,"
                + " m.match_basis,m.day_difference,m.matched_by,m.matched_at,"
                + " l.amount,l.currency,l.direction,l.occurred_at,l.external_reference,l.counterparty"
                + " from finance_bank_recon_match m"
                + " join finance_bank_recon_statement_line l"
                + "   on l.tenant_id = m.tenant_id and l.id = m.statement_line_id"
                + " where m.session_id = ? order by l.occurred_at, m.id limit ? offset ?",
            sessionId,
            pageSize,
            pageNo * pageSize);
    return new FinanceBankReconMatchPage(
        rows.map(BankReconciliationService::matchRow), pageNo, pageSize, total);
  }

  /** 未匹配清单（真分页 + 真筛选）。 */
  public FinanceBankReconUnmatchedPage unmatched(
      UUID sessionId, String status, String side, int page, int size) {
    requireSession(sessionId);
    int pageNo = Math.max(0, page);
    int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    var where = new StringBuilder(" where session_id = ?");
    var args = new ArrayList<Object>();
    args.add(sessionId);
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      args.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (side != null && !side.isBlank()) {
      where.append(" and side = ?");
      args.add(side.trim().toUpperCase(Locale.ROOT));
    }

    var total =
        sql.fetchOne("select count(*) from finance_bank_recon_unmatched" + where, args.toArray())
            .get(0, Long.class);
    var bindings = new ArrayList<Object>(args);
    bindings.add(pageSize);
    bindings.add(pageNo * pageSize);
    var rows =
        sql.fetch(
            "select id,session_id,side,statement_line_id,system_line_id,occurred_at,direction,"
                + " amount,currency,counterparty,external_reference,memo,status,"
                + " claimed_document_type,claimed_document_id,claimed_document_no,claimed_by,"
                + " claimed_at,claim_reason,exclude_reason,created_at,updated_at"
                + " from finance_bank_recon_unmatched"
                + where
                + " order by occurred_at, id limit ? offset ?",
            bindings.toArray());
    return new FinanceBankReconUnmatchedPage(
        rows.map(BankReconciliationService::unmatchedRow), pageNo, pageSize, total);
  }

  /** 认领 / 说明记录（真分页）。 */
  public FinanceBankReconClaimPage claims(UUID sessionId, UUID unmatchedId, int page, int size) {
    requireSession(sessionId);
    int pageNo = Math.max(0, page);
    int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    var where = new StringBuilder(" where session_id = ?");
    var args = new ArrayList<Object>();
    args.add(sessionId);
    if (unmatchedId != null) {
      where.append(" and unmatched_id = ?");
      args.add(unmatchedId);
    }

    var total =
        sql.fetchOne("select count(*) from finance_bank_recon_claim" + where, args.toArray())
            .get(0, Long.class);
    var bindings = new ArrayList<Object>(args);
    bindings.add(pageSize);
    bindings.add(pageNo * pageSize);
    var rows =
        sql.fetch(
            "select id,session_id,unmatched_id,action,claimed_document_type,claimed_document_id,"
                + " claimed_document_no,reason,claimed_by,claimed_at,created_at"
                + " from finance_bank_recon_claim"
                + where
                + " order by claimed_at desc, id desc limit ? offset ?",
            bindings.toArray());
    return new FinanceBankReconClaimPage(
        rows.map(BankReconciliationService::claimRow), pageNo, pageSize, total);
  }

  /** 事件流（真分页）。 */
  public FinanceBankReconEventPage events(UUID sessionId, int page, int size) {
    requireSession(sessionId);
    int pageNo = Math.max(0, page);
    int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    var total =
        sql.fetchOne("select count(*) from finance_bank_recon_event where session_id = ?", sessionId)
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select id,session_id,event_type,summary,payload,operator_user_id,occurred_at,created_at"
                + " from finance_bank_recon_event where session_id = ?"
                + " order by occurred_at desc, id desc limit ? offset ?",
            sessionId,
            pageSize,
            pageNo * pageSize);
    return new FinanceBankReconEventPage(
        rows.map(BankReconciliationService::eventRow), pageNo, pageSize, total);
  }

  // ================================================================ 人工认领

  /**
   * 人工认领到具体单据。
   *
   * <p>⚠️ 三道闸：① 未匹配项必须存在且**仍是待处理**（已认领 / 已排除是终态）；
   * ② 认领目标必须是**具体单据**（RECEIPT / PAYMENT_ORDER / SETTLEMENT 必须给 id，
   * 且该单据**真的存在于本租户** ⇒ DB 触发器再挡一次）；
   * ③ 认领**只写本模块的表**，不改被认领单据的状态。
   */
  @Transactional
  public FinanceBankReconUnmatchedRow claim(
      UUID sessionId, UUID unmatchedId, FinanceBankReconClaimInput input, AccessService.Access actor) {
    var unmatched = requireUnmatched(sessionId, unmatchedId);
    if (!"OPEN".equals(unmatched.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该未匹配项已处理过（当前状态 "
              + unmatched.get("status", String.class)
              + "），不能重复认领");
    }
    if (input.documentType() == FinanceBankReconDocumentType.OTHER) {
      // 其它单据：本模块不校验它的存在性 ⇒ 必须写清单据号。
      if (input.documentNo() == null || input.documentNo().trim().isEmpty()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "认领到其它单据时必须写清单据号");
      }
    } else {
      if (input.documentId() == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "认领到具体单据时必须提供单据 id");
      }
      if (input.documentNo() == null || input.documentNo().trim().isEmpty()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "认领到具体单据时必须写清单据号");
      }
      verifyClaimTarget(actor.tenantId(), input);
    }

    var now = nowUtc();
    sql.execute(
        "update finance_bank_recon_unmatched set status = 'CLAIMED',"
            + " claimed_document_type = ?, claimed_document_id = ?, claimed_document_no = ?,"
            + " claimed_by = ?, claimed_at = ?::timestamptz, claim_reason = ?,"
            + " updated_at = ?::timestamptz where id = ? and tenant_id = ?",
        input.documentType().name(),
        input.documentId(),
        input.documentNo().trim(),
        actor.userId(),
        now,
        input.reason().trim(),
        now,
        unmatchedId,
        actor.tenantId());

    sql.execute(
        "insert into finance_bank_recon_claim(id,tenant_id,session_id,unmatched_id,action,"
            + " claimed_document_type,claimed_document_id,claimed_document_no,reason,claimed_by,"
            + " claimed_at,created_at)"
            + " values (?,?,?,?,'CLAIM',?,?,?,?,?,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        actor.tenantId(),
        sessionId,
        unmatchedId,
        input.documentType().name(),
        input.documentId(),
        input.documentNo().trim(),
        input.reason().trim(),
        actor.userId(),
        now,
        now);

    appendEvent(
        actor.tenantId(),
        sessionId,
        "UNMATCHED_CLAIMED",
        "未匹配项认领到 " + input.documentType().label() + " " + input.documentNo().trim(),
        Map.of(
            "unmatchedId", unmatchedId.toString(),
            "documentType", input.documentType().name(),
            "documentNo", input.documentNo().trim()),
        actor.userId(),
        now);

    refreshUnmatchedCounts(actor.tenantId(), sessionId);
    return unmatchedRow(requireUnmatched(sessionId, unmatchedId));
  }

  /** 排除一条未匹配项（必须写理由）。 */
  @Transactional
  public FinanceBankReconUnmatchedRow exclude(
      UUID sessionId, UUID unmatchedId, FinanceBankReconExcludeInput input, AccessService.Access actor) {
    var unmatched = requireUnmatched(sessionId, unmatchedId);
    if (!"OPEN".equals(unmatched.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该未匹配项已处理过（当前状态 " + unmatched.get("status", String.class) + "），不能重复排除");
    }
    var now = nowUtc();
    sql.execute(
        "update finance_bank_recon_unmatched set status = 'EXCLUDED', exclude_reason = ?,"
            + " updated_at = ?::timestamptz where id = ? and tenant_id = ?",
        input.reason().trim(),
        now,
        unmatchedId,
        actor.tenantId());

    sql.execute(
        "insert into finance_bank_recon_claim(id,tenant_id,session_id,unmatched_id,action,"
            + " claimed_document_type,claimed_document_id,claimed_document_no,reason,claimed_by,"
            + " claimed_at,created_at)"
            + " values (?,?,?,?,'EXCLUDE',null,null,'',?,?,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        actor.tenantId(),
        sessionId,
        unmatchedId,
        input.reason().trim(),
        actor.userId(),
        now,
        now);

    appendEvent(
        actor.tenantId(),
        sessionId,
        "UNMATCHED_EXCLUDED",
        "未匹配项已排除：" + input.reason().trim(),
        Map.of("unmatchedId", unmatchedId.toString()),
        actor.userId(),
        now);

    refreshUnmatchedCounts(actor.tenantId(), sessionId);
    return unmatchedRow(requireUnmatched(sessionId, unmatchedId));
  }

  // ================================================================ 对账报告

  /**
   * 生成对账报告并确认对账。
   *
   * <p>🔴 报告里的**未匹配金额只算「待处理」**（OPEN）的那些：
   * 已认领 / 已排除的差异**已被人工解决**，不该再进调节表（否则账永远平不了）。
   */
  @Transactional
  public FinanceBankReconReportRow reconcile(UUID sessionId, AccessService.Access actor) {
    var session = requireSession(sessionId);
    if ("RECONCILED".equals(session.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "对账批次已确认，不能重复确认");
    }
    if (!"MATCHED".equals(session.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "对账批次必须先跑过自动匹配才能确认");
    }

    var currency = session.get("currency", String.class);
    var periodStart = session.get("period_start", LocalDate.class);
    var periodEnd = session.get("period_end", LocalDate.class);
    var accountId = session.get("account_id", UUID.class);

    var statementIn = sumDirection(sessionId, "finance_bank_recon_statement_line", "IN");
    var statementOut = sumDirection(sessionId, "finance_bank_recon_statement_line", "OUT");
    var systemIn = sumDirection(sessionId, "finance_bank_recon_system_line", "IN");
    var systemOut = sumDirection(sessionId, "finance_bank_recon_system_line", "OUT");

    var openBankIn = sumUnmatched(sessionId, "BANK", "OPEN", "IN");
    var openBankOut = sumUnmatched(sessionId, "BANK", "OPEN", "OUT");
    var openSystemIn = sumUnmatched(sessionId, "SYSTEM", "OPEN", "IN");
    var openSystemOut = sumUnmatched(sessionId, "SYSTEM", "OPEN", "OUT");

    var matchedAmount =
        money(
            sql.fetchOne(
                    "select coalesce(sum(l.amount),0) from finance_bank_recon_match m"
                        + " join finance_bank_recon_statement_line l"
                        + "   on l.tenant_id = m.tenant_id and l.id = m.statement_line_id"
                        + " where m.session_id = ?",
                    sessionId)
                .get(0, BigDecimal.class));

    var statementCount = countStatementLines(sessionId);
    var systemCount =
        sql.fetchOne("select count(*) from finance_bank_recon_system_line where session_id = ?", sessionId)
            .get(0, Integer.class);
    var matchedCount =
        sql.fetchOne("select count(*) from finance_bank_recon_match where session_id = ?", sessionId)
            .get(0, Integer.class);
    var openBankCount = countUnmatched(sessionId, "BANK", "OPEN");
    var openSystemCount = countUnmatched(sessionId, "SYSTEM", "OPEN");
    var claimedCount = countUnmatched(sessionId, null, "CLAIMED");
    var excludedCount = countUnmatched(sessionId, null, "EXCLUDED");

    var statementClosing = money(session.get("statement_closing_balance", BigDecimal.class));
    var bookClosing = bookBalanceAt(actor.tenantId(), accountId, periodEnd, false);

    BigDecimal adjusted = null;
    BigDecimal difference = null;
    if (bookClosing != null) {
      adjusted =
          money(
              statementClosing
                  .add(openBankIn)
                  .subtract(openBankOut)
                  .subtract(openSystemIn)
                  .add(openSystemOut));
      difference = money(adjusted.subtract(bookClosing));
    }

    var now = nowUtc();
    var reportId = UUID.randomUUID();
    sql.execute(
        "insert into finance_bank_recon_report(id,tenant_id,session_id,currency,period_start,"
            + " period_end,statement_line_count,system_line_count,matched_count,"
            + " unmatched_bank_count,unmatched_system_count,claimed_count,excluded_count,"
            + " matched_amount,statement_in_amount,statement_out_amount,system_in_amount,"
            + " system_out_amount,unmatched_bank_in_amount,unmatched_bank_out_amount,"
            + " unmatched_system_in_amount,unmatched_system_out_amount,statement_opening_balance,"
            + " statement_closing_balance,book_closing_balance,book_balance_available,"
            + " adjusted_balance,difference,generated_by,generated_at,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,"
            + " ?::timestamptz)"
            + " on conflict (tenant_id,session_id) do update set"
            + " statement_line_count = excluded.statement_line_count,"
            + " system_line_count = excluded.system_line_count,"
            + " matched_count = excluded.matched_count,"
            + " unmatched_bank_count = excluded.unmatched_bank_count,"
            + " unmatched_system_count = excluded.unmatched_system_count,"
            + " claimed_count = excluded.claimed_count, excluded_count = excluded.excluded_count,"
            + " matched_amount = excluded.matched_amount,"
            + " statement_in_amount = excluded.statement_in_amount,"
            + " statement_out_amount = excluded.statement_out_amount,"
            + " system_in_amount = excluded.system_in_amount,"
            + " system_out_amount = excluded.system_out_amount,"
            + " unmatched_bank_in_amount = excluded.unmatched_bank_in_amount,"
            + " unmatched_bank_out_amount = excluded.unmatched_bank_out_amount,"
            + " unmatched_system_in_amount = excluded.unmatched_system_in_amount,"
            + " unmatched_system_out_amount = excluded.unmatched_system_out_amount,"
            + " statement_opening_balance = excluded.statement_opening_balance,"
            + " statement_closing_balance = excluded.statement_closing_balance,"
            + " book_closing_balance = excluded.book_closing_balance,"
            + " book_balance_available = excluded.book_balance_available,"
            + " adjusted_balance = excluded.adjusted_balance,"
            + " difference = excluded.difference,"
            + " generated_by = excluded.generated_by, generated_at = excluded.generated_at",
        reportId,
        actor.tenantId(),
        sessionId,
        currency,
        periodStart,
        periodEnd,
        statementCount,
        systemCount,
        matchedCount,
        openBankCount,
        openSystemCount,
        claimedCount,
        excludedCount,
        matchedAmount,
        statementIn,
        statementOut,
        systemIn,
        systemOut,
        openBankIn,
        openBankOut,
        openSystemIn,
        openSystemOut,
        money(session.get("statement_opening_balance", BigDecimal.class)),
        statementClosing,
        bookClosing,
        bookClosing != null,
        adjusted,
        difference,
        actor.userId(),
        now,
        now);

    sql.execute(
        "update finance_bank_recon_session set status = 'RECONCILED', reconciled_by = ?,"
            + " reconciled_at = ?::timestamptz, version = version + 1, updated_at = ?::timestamptz"
            + " where id = ? and tenant_id = ?",
        actor.userId(),
        now,
        now,
        sessionId,
        actor.tenantId());

    appendEvent(
        actor.tenantId(),
        sessionId,
        "REPORT_GENERATED",
        "生成对账报告：匹配 " + matchedCount + " 对，待处理差异 "
            + (openBankCount + openSystemCount) + " 笔",
        Map.of(
            "matched", matchedCount,
            "openBank", openBankCount,
            "openSystem", openSystemCount,
            "difference", difference == null ? "UNAVAILABLE" : difference.toPlainString()),
        actor.userId(),
        now);

    return report(sessionId);
  }

  /** 读已生成的对账报告（没有 ⇒ 404）。 */
  public FinanceBankReconReportRow report(UUID sessionId) {
    requireSession(sessionId);
    var row =
        sql.fetchOne(
            "select r.id,r.session_id,r.currency,r.period_start,r.period_end,"
                + " r.statement_line_count,r.system_line_count,r.matched_count,"
                + " r.unmatched_bank_count,r.unmatched_system_count,r.claimed_count,r.excluded_count,"
                + " r.matched_amount,r.statement_in_amount,r.statement_out_amount,"
                + " r.system_in_amount,r.system_out_amount,r.unmatched_bank_in_amount,"
                + " r.unmatched_bank_out_amount,r.unmatched_system_in_amount,"
                + " r.unmatched_system_out_amount,r.statement_opening_balance,"
                + " r.statement_closing_balance,r.book_closing_balance,r.book_balance_available,"
                + " r.adjusted_balance,r.difference,r.generated_by,r.generated_at"
                + " from finance_bank_recon_report r where r.session_id = ?",
            sessionId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该对账批次还没有生成对账报告");
    }
    var reconNo =
        sql.fetchOne("select recon_no from finance_bank_recon_session where id = ?", sessionId)
            .get(0, String.class);
    return new FinanceBankReconReportRow(
        row.get("id", UUID.class),
        row.get("session_id", UUID.class),
        reconNo,
        row.get("currency", String.class),
        row.get("period_start", LocalDate.class),
        row.get("period_end", LocalDate.class),
        row.get("statement_line_count", Integer.class),
        row.get("system_line_count", Integer.class),
        row.get("matched_count", Integer.class),
        row.get("unmatched_bank_count", Integer.class),
        row.get("unmatched_system_count", Integer.class),
        row.get("claimed_count", Integer.class),
        row.get("excluded_count", Integer.class),
        row.get("matched_amount", BigDecimal.class),
        row.get("statement_in_amount", BigDecimal.class),
        row.get("statement_out_amount", BigDecimal.class),
        row.get("system_in_amount", BigDecimal.class),
        row.get("system_out_amount", BigDecimal.class),
        row.get("unmatched_bank_in_amount", BigDecimal.class),
        row.get("unmatched_bank_out_amount", BigDecimal.class),
        row.get("unmatched_system_in_amount", BigDecimal.class),
        row.get("unmatched_system_out_amount", BigDecimal.class),
        row.get("statement_opening_balance", BigDecimal.class),
        row.get("statement_closing_balance", BigDecimal.class),
        row.get("book_closing_balance", BigDecimal.class),
        row.get("book_balance_available", Boolean.class),
        row.get("adjusted_balance", BigDecimal.class),
        row.get("difference", BigDecimal.class),
        row.get("generated_by", UUID.class),
        row.get("generated_at", OffsetDateTime.class));
  }

  // ================================================================ 匹配规则

  /** 匹配规则列表（真分页）。 */
  public FinanceBankReconRulePage rules(int page, int size) {
    int pageNo = Math.max(0, page);
    int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    var total =
        sql.fetchOne("select count(*) from finance_bank_recon_rule").get(0, Long.class);
    var rows =
        sql.fetch(
            "select id,rule_code,rule_name,rule_type,priority,enabled,date_window_days,"
                + " amount_tolerance,counterparty_required,builtin,note,created_at,updated_at"
                + " from finance_bank_recon_rule order by priority, rule_code limit ? offset ?",
            pageSize,
            pageNo * pageSize);
    return new FinanceBankReconRulePage(
        rows.map(BankReconciliationService::ruleRow), pageNo, pageSize, total);
  }

  /** 新建一条匹配规则（规则表**不预置任何一行**，租户自己维护）。 */
  @Transactional
  public FinanceBankReconRuleRow createRule(
      FinanceBankReconRuleInput input, AccessService.Access actor) {
    var code = input.ruleCode().trim().toUpperCase(Locale.ROOT);
    var exists =
        sql.fetchOne("select 1 from finance_bank_recon_rule where rule_code = ?", code) != null;
    if (exists) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "规则编码已存在：" + code);
    }
    var window = input.dateWindowDays() == null ? 3 : input.dateWindowDays();
    if (window < 1 || window > 31) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "日期窗口必须在 1–31 天之间（窗口过大等于用「金额相同」冒充匹配）");
    }
    var id = UUID.randomUUID();
    var now = nowUtc();
    sql.execute(
        "insert into finance_bank_recon_rule(id,tenant_id,rule_code,rule_name,rule_type,priority,"
            + " enabled,date_window_days,amount_tolerance,counterparty_required,builtin,note,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,false,?,?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        code,
        input.ruleName().trim(),
        input.ruleType().name(),
        input.priority() == null ? 100 : input.priority(),
        input.enabled() == null || input.enabled(),
        window,
        money(input.amountTolerance()),
        input.counterpartyRequired() != null && input.counterpartyRequired(),
        trim(input.note()),
        now,
        now);
    return ruleRow(requireRule(id));
  }

  /** 改一条匹配规则（**编码不可改** —— 编码是历史匹配的追溯依据）。 */
  @Transactional
  public FinanceBankReconRuleRow updateRule(
      UUID id, FinanceBankReconRuleInput input, AccessService.Access actor) {
    var current = requireRule(id);
    var code = input.ruleCode().trim().toUpperCase(Locale.ROOT);
    if (!code.equals(current.get("rule_code", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "规则编码不可改：编码是历史匹配的追溯依据");
    }
    var window = input.dateWindowDays() == null ? 3 : input.dateWindowDays();
    if (window < 1 || window > 31) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "日期窗口必须在 1–31 天之间（窗口过大等于用「金额相同」冒充匹配）");
    }
    sql.execute(
        "update finance_bank_recon_rule set rule_name = ?, rule_type = ?, priority = ?,"
            + " enabled = ?, date_window_days = ?, amount_tolerance = ?,"
            + " counterparty_required = ?, note = ?, updated_at = ?::timestamptz"
            + " where id = ? and tenant_id = ?",
        input.ruleName().trim(),
        input.ruleType().name(),
        input.priority() == null ? 100 : input.priority(),
        input.enabled() == null || input.enabled(),
        window,
        money(input.amountTolerance()),
        input.counterpartyRequired() != null && input.counterpartyRequired(),
        trim(input.note()),
        nowUtc(),
        id,
        actor.tenantId());
    return ruleRow(requireRule(id));
  }

  // ================================================================ 内部：系统侧候选集

  /** 参与匹配的候选（一侧的行）。 */
  private record Candidate(
      UUID id,
      OffsetDateTime occurredAt,
      String direction,
      BigDecimal amount,
      String currency,
      String externalReference,
      String counterparty) {}

  /** 参与匹配的规则。 */
  private record Rule(
      String code,
      FinanceBankReconRuleType type,
      int windowDays,
      BigDecimal tolerance,
      boolean counterpartyRequired) {}

  /**
   * 从**同服务的真实上游表**刷新系统侧候选快照。
   *
   * <p>候选集口径：<b>同币种 + 期间内</b>；并把「这条记录自己声明挂在哪个账户上」
   * 当作附加过滤（声明了**别的**账户 ⇒ 排除；没有声明 ⇒ 仍是候选）。
   *
   * <p>⚠️ 快照**只增不删**（UPSERT）：上游 12.2 / 12.3 的记录是**状态机**而不是物理删除
   * （作废 = 改状态），所以「上游记录消失了」这件事不会发生；
   * 万一消失了，保留旧快照比悄悄删掉更安全（差额会显式地不平，而不是被抹平）。
   */
  private int refreshSystemLines(
      UUID tenantId,
      UUID sessionId,
      UUID accountId,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd) {
    var from = periodStart.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    var to = periodEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    var inserted = 0;

    // ---- 12.2.1.1 收款单（资金流入）----
    var receipts =
        sql.fetch(
            "select id,receipt_no,currency,amount,received_at,payer_name,source_reference,"
                + " fund_account_ref,fund_account_label,status"
                + " from finance_receipt"
                + " where currency = ? and received_at >= ?::timestamptz and received_at < ?::timestamptz"
                + " and status <> 'VOID'"
                + " and (fund_account_ref is null or fund_account_ref = ?)"
                + " order by received_at, id",
            currency,
            from,
            to,
            accountId);
    for (var r : receipts) {
      inserted +=
          upsertSystemLine(
              tenantId,
              sessionId,
              FinanceBankReconSourceType.RECEIPT,
              r.get("id", UUID.class),
              r.get("receipt_no", String.class),
              "收款单 " + r.get("receipt_no", String.class),
              r.get("received_at", OffsetDateTime.class),
              "SECOND",
              "IN",
              r.get("amount", BigDecimal.class),
              r.get("amount", BigDecimal.class),
              currency,
              r.get("source_reference", String.class) == null ? "" : r.get("source_reference", String.class),
              r.get("payer_name", String.class),
              r.get("fund_account_label", String.class));
    }

    // ---- 12.2.2.1 付款单（资金流出）----
    var payments =
        sql.fetch(
            "select id,order_no,currency,amount,created_at,approved_at,submitted_at,"
                + " counterparty_name,payment_account_id,payment_account_name,status"
                + " from finance_payment_order"
                + " where currency = ? and created_at >= ?::timestamptz and created_at < ?::timestamptz"
                + " and status <> 'DRAFT' and status <> 'FAILED'"
                + " and (payment_account_id is null or payment_account_id = ?)"
                + " order by created_at, id",
            currency,
            from,
            to,
            accountId);
    for (var r : payments) {
      var occurredAt =
          r.get("approved_at", OffsetDateTime.class) != null
              ? r.get("approved_at", OffsetDateTime.class)
              : r.get("submitted_at", OffsetDateTime.class) != null
                  ? r.get("submitted_at", OffsetDateTime.class)
                  : r.get("created_at", OffsetDateTime.class);
      inserted +=
          upsertSystemLine(
              tenantId,
              sessionId,
              FinanceBankReconSourceType.PAYMENT_ORDER,
              r.get("id", UUID.class),
              r.get("order_no", String.class),
              "付款单 " + r.get("order_no", String.class),
              occurredAt,
              "SECOND",
              "OUT",
              r.get("amount", BigDecimal.class),
              r.get("amount", BigDecimal.class),
              currency,
              r.get("order_no", String.class),
              r.get("counterparty_name", String.class),
              r.get("payment_account_name", String.class));
    }

    // ---- 12.3.1.2 结算明细行（12.3.1.1 的结算单明细）----
    var settlementLines =
        sql.fetch(
            "select l.id,l.settlement_id,l.category,l.amount,l.raw_amount,l.currency,l.occurred_on,"
                + " l.external_reference,l.description,s.settlement_no,s.status,s.period_start,"
                + " s.period_end,s.currency as settlement_currency"
                + " from finance_settlement_line l"
                + " join finance_settlement s"
                + "   on s.tenant_id = l.tenant_id and s.id = l.settlement_id"
                + " where l.currency = ? and s.status <> 'VOID' and s.status <> 'SUPERSEDED'"
                + " and coalesce(l.occurred_on, s.period_start) >= ? and coalesce(l.occurred_on,"
                + " s.period_end) <= ?"
                + " order by l.occurred_on, l.id",
            currency,
            periodStart,
            periodEnd);
    for (var r : settlementLines) {
      var raw = r.get("raw_amount", BigDecimal.class);
      var direction = raw.signum() < 0 ? "OUT" : "IN";
      var occurredOn = r.get("occurred_on", LocalDate.class);
      var occurredAt =
          (occurredOn == null ? r.get("period_start", LocalDate.class) : occurredOn)
              .atStartOfDay(ZoneOffset.UTC)
              .toOffsetDateTime();
      inserted +=
          upsertSystemLine(
              tenantId,
              sessionId,
              FinanceBankReconSourceType.SETTLEMENT_LINE,
              r.get("id", UUID.class),
              r.get("settlement_no", String.class) + "#" + r.get("id", UUID.class).toString().substring(0, 8),
              "结算明细 " + r.get("settlement_no", String.class) + " / " + r.get("category", String.class),
              occurredAt,
              "DAY",
              direction,
              money(raw.abs()),
              money(raw),
              currency,
              r.get("external_reference", String.class) == null ? "" : r.get("external_reference", String.class),
              "",
              r.get("description", String.class));
    }

    return sql.fetchOne(
            "select count(*) from finance_bank_recon_system_line where session_id = ?", sessionId)
        .get(0, Integer.class);
  }

  /** 写入 / 更新一条系统记录快照（id 由 (批次, 来源, 来源 id) 确定性派生 ⇒ 稳定）。 */
  private int upsertSystemLine(
      UUID tenantId,
      UUID sessionId,
      FinanceBankReconSourceType sourceType,
      UUID sourceId,
      String sourceNo,
      String sourceLabel,
      OffsetDateTime occurredAt,
      String precision,
      String direction,
      BigDecimal amount,
      BigDecimal rawAmount,
      String currency,
      String externalReference,
      String counterparty,
      String memo) {
    var id =
        UUID.nameUUIDFromBytes(
            ("starcon-finance-bank-recon-system-" + sessionId + "-" + sourceType.name() + "-" + sourceId)
                .getBytes(StandardCharsets.UTF_8));
    var affected =
        sql.execute(
            "insert into finance_bank_recon_system_line(id,tenant_id,session_id,source_type,"
                + " source_id,source_no,source_label,occurred_at,occurred_precision,direction,amount,"
                + " raw_amount,currency,external_reference,counterparty,memo,created_at,updated_at)"
                + " values (?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)"
                + " on conflict (tenant_id,session_id,source_type,source_id) do update set"
                + " source_no = excluded.source_no, source_label = excluded.source_label,"
                + " occurred_at = excluded.occurred_at, occurred_precision = excluded.occurred_precision,"
                + " direction = excluded.direction, amount = excluded.amount,"
                + " raw_amount = excluded.raw_amount, external_reference = excluded.external_reference,"
                + " counterparty = excluded.counterparty, memo = excluded.memo,"
                + " updated_at = excluded.updated_at",
            id,
            tenantId,
            sessionId,
            sourceType.name(),
            sourceId,
            sourceNo == null ? "" : sourceNo,
            sourceLabel == null ? "" : sourceLabel,
            occurredAt,
            precision,
            direction,
            money(amount),
            money(rawAmount),
            currency,
            externalReference == null ? "" : externalReference,
            counterparty == null ? "" : counterparty,
            memo == null ? "" : memo,
            nowUtc(),
            nowUtc());
    return affected > 0 ? 1 : 0;
  }

  // ================================================================ 内部：匹配判定

  /** 载入本租户启用的规则（按优先级）。 */
  private List<Rule> loadRules(UUID tenantId) {
    return sql.fetch(
            "select rule_code,rule_type,date_window_days,amount_tolerance,counterparty_required"
                + " from finance_bank_recon_rule where enabled = true"
                + " order by priority, rule_code")
        .map(
            r ->
                new Rule(
                    r.get("rule_code", String.class),
                    FinanceBankReconRuleType.valueOf(r.get("rule_type", String.class)),
                    r.get("date_window_days", Integer.class),
                    r.get("amount_tolerance", BigDecimal.class),
                    r.get("counterparty_required", Boolean.class)));
  }

  /**
   * 候选判定。
   *
   * <p>🔴 <b>卷 12 硬约束</b>：金额相同**只是候选**，必须再结合其它维度。
   * 三种规则类型各自的附加条件：
   *
   * <ul>
   *   <li>{@code REFERENCE_EXACT}：对方单号**非空且相等**（否则不算命中）；</li>
   *   <li>{@code COUNTERPARTY_AMOUNT}：对手方**非空且规范化后相等**；</li>
   *   <li>{@code AMOUNT_DATE_WINDOW}：对手方**不矛盾**（两边都有名字却不一致 ⇒ 不是候选）。</li>
   * </ul>
   *
   * <p>共同条件：方向相同 + 币种相同 + 金额差 ≤ 容差 + 日期差 ≤ 窗口。
   */
  private boolean isMatchCandidate(Candidate statement, Candidate system, Rule rule) {
    if (!statement.direction().equals(system.direction())) return false;
    if (!statement.currency().equals(system.currency())) return false;
    if (statement.amount() == null || system.amount() == null) return false;

    var tolerance = rule.tolerance() == null ? BigDecimal.ZERO : rule.tolerance();
    if (statement.amount().subtract(system.amount()).abs().compareTo(tolerance) > 0) return false;

    if (Math.abs(dayDifference(statement.occurredAt(), system.occurredAt())) > rule.windowDays()) {
      return false;
    }

    switch (rule.type()) {
      case REFERENCE_EXACT -> {
        var statementRef = normalizeReference(statement.externalReference());
        var systemRef = normalizeReference(system.externalReference());
        return !statementRef.isEmpty() && statementRef.equals(systemRef);
      }
      case COUNTERPARTY_AMOUNT -> {
        var statementParty = normalizeCounterparty(statement.counterparty());
        var systemParty = normalizeCounterparty(system.counterparty());
        return !statementParty.isEmpty() && statementParty.equals(systemParty);
      }
      case AMOUNT_DATE_WINDOW -> {
        var statementParty = normalizeCounterparty(statement.counterparty());
        var systemParty = normalizeCounterparty(system.counterparty());
        if (rule.counterpartyRequired()) {
          return !statementParty.isEmpty() && statementParty.equals(systemParty);
        }
        // 对手方**不矛盾**：两边都写了名字却不一致 ⇒ 不是同一笔。
        return statementParty.isEmpty() || systemParty.isEmpty() || statementParty.equals(systemParty);
      }
      default -> {
        return false;
      }
    }
  }

  /** 命中依据的人话描述（能自证「为什么这两行配成一对」）。 */
  private String matchBasis(Candidate statement, Candidate system, Rule rule) {
    var parts = new ArrayList<String>();
    switch (rule.type()) {
      case REFERENCE_EXACT -> parts.add("对方单号相等");
      case COUNTERPARTY_AMOUNT -> parts.add("对手方相等");
      case AMOUNT_DATE_WINDOW -> parts.add("对手方不矛盾");
      default -> parts.add("规则命中");
    }
    parts.add("金额相等");
    parts.add("方向相同");
    parts.add("日期相差 " + dayDifference(statement.occurredAt(), system.occurredAt()) + " 天");
    return String.join(" + ", parts) + "（规则 " + rule.code() + "）";
  }

  /** 两天数之差（按**日历日**算，天精度的来源按整天比）。 */
  private static int dayDifference(OffsetDateTime left, OffsetDateTime right) {
    if (left == null || right == null) return Integer.MAX_VALUE;
    return (int) ChronoUnit.DAYS.between(left.toLocalDate(), right.toLocalDate());
  }

  /** 单号规范化：去空白 + 转小写。 */
  private static String normalizeReference(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 对手方规范化：去空白 + 去标点 + 转小写。
   *
   * <p>（银行账单里写「ACME CO., LTD.」、系统里写「Acme Co Ltd」是常态。）
   */
  private static String normalizeCounterparty(String value) {
    if (value == null) return "";
    var sb = new StringBuilder();
    for (char c : value.trim().toLowerCase(Locale.ROOT).toCharArray()) {
      if (Character.isLetterOrDigit(c)) sb.append(c);
    }
    return sb.toString();
  }

  // ================================================================ 内部：未匹配项

  /** 确保一条未匹配项存在（**已有则不重复插入**）。 */
  private void ensureUnmatchedRow(
      UUID tenantId, UUID sessionId, Candidate candidate, String side) {
    var exists =
        side.equals("BANK")
            ? sql.fetchOne(
                    "select 1 from finance_bank_recon_unmatched"
                        + " where session_id = ? and statement_line_id = ?",
                    sessionId,
                    candidate.id())
                != null
            : sql.fetchOne(
                    "select 1 from finance_bank_recon_unmatched"
                        + " where session_id = ? and system_line_id = ?",
                    sessionId,
                    candidate.id())
                != null;
    if (exists) return;

    // 事实快照：银行侧取流水行，系统侧取系统记录行。
    String counterparty;
    String externalReference;
    String memo;
    if (side.equals("BANK")) {
      var row =
          sql.fetchOne(
              "select external_reference,counterparty,memo from"
                  + " finance_bank_recon_statement_line where id = ?",
              candidate.id());
      counterparty = row == null ? "" : row.get("counterparty", String.class);
      externalReference = row == null ? "" : row.get("external_reference", String.class);
      memo = row == null ? "" : row.get("memo", String.class);
    } else {
      var row =
          sql.fetchOne(
              "select external_reference,counterparty,memo from finance_bank_recon_system_line"
                  + " where id = ?",
              candidate.id());
      counterparty = row == null ? "" : row.get("counterparty", String.class);
      externalReference = row == null ? "" : row.get("external_reference", String.class);
      memo = row == null ? "" : row.get("memo", String.class);
    }

    sql.execute(
        "insert into finance_bank_recon_unmatched(id,tenant_id,session_id,side,statement_line_id,"
            + " system_line_id,occurred_at,direction,amount,currency,counterparty,"
            + " external_reference,memo,status,claimed_document_type,claimed_document_id,"
            + " claimed_document_no,claimed_by,claimed_at,claim_reason,exclude_reason,created_at,"
            + " updated_at)"
            + " values (?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,'OPEN',null,null,'',null,null,"
            + " '','',?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        sessionId,
        side,
        side.equals("BANK") ? candidate.id() : null,
        side.equals("SYSTEM") ? candidate.id() : null,
        candidate.occurredAt(),
        candidate.direction(),
        money(candidate.amount()),
        candidate.currency(),
        counterparty,
        externalReference,
        memo,
        nowUtc(),
        nowUtc());
  }

  // ================================================================ 内部：工具

  /** 取一行对账批次（不存在 ⇒ 404）。 */
  private Record requireSession(UUID id) {
    var row =
        sql.fetchOne(
            "select id,recon_no,account_id,account_no,account_name,currency,period_start,"
                + " period_end,statement_opening_balance,statement_closing_balance,"
                + " book_opening_balance,book_closing_balance,status,source_system,"
                + " statement_line_count,system_line_count,matched_count,unmatched_bank_count,"
                + " unmatched_system_count,reconciled_by,reconciled_at,note,version,created_at,"
                + " updated_at from finance_bank_recon_session where id = ?",
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对账批次不存在：" + id);
    }
    return row;
  }

  /** 取一行未匹配项（不存在 ⇒ 404）。 */
  private Record requireUnmatched(UUID sessionId, UUID unmatchedId) {
    var row =
        sql.fetchOne(
            "select id,session_id,side,statement_line_id,system_line_id,occurred_at,direction,"
                + " amount,currency,counterparty,external_reference,memo,status,"
                + " claimed_document_type,claimed_document_id,claimed_document_no,claimed_by,"
                + " claimed_at,claim_reason,exclude_reason,created_at,updated_at"
                + " from finance_bank_recon_unmatched where id = ? and session_id = ?",
            unmatchedId,
            sessionId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未匹配项不存在：" + unmatchedId);
    }
    return row;
  }

  /** 取一行匹配规则（不存在 ⇒ 404）。 */
  private Record requireRule(UUID id) {
    var row =
        sql.fetchOne(
            "select id,rule_code,rule_name,rule_type,priority,enabled,date_window_days,"
                + " amount_tolerance,counterparty_required,builtin,note,created_at,updated_at"
                + " from finance_bank_recon_rule where id = ?",
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "匹配规则不存在：" + id);
    }
    return row;
  }

  /** 校验认领目标单据**真的存在于本租户**（DB 触发器还会再挡一次）。 */
  private void verifyClaimTarget(UUID tenantId, FinanceBankReconClaimInput input) {
    var count =
        switch (input.documentType()) {
          case RECEIPT ->
              sql.fetchOne(
                      "select count(*) from finance_receipt where tenant_id = ? and id = ?",
                      tenantId,
                      input.documentId())
                  .get(0, Long.class);
          case PAYMENT_ORDER ->
              sql.fetchOne(
                      "select count(*) from finance_payment_order where tenant_id = ? and id = ?",
                      tenantId,
                      input.documentId())
                  .get(0, Long.class);
          case SETTLEMENT ->
              sql.fetchOne(
                      "select count(*) from finance_settlement where tenant_id = ? and id = ?",
                      tenantId,
                      input.documentId())
                  .get(0, Long.class);
          case OTHER -> 1L;
        };
    if (count == 0L) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "认领目标不存在或不属于当前租户：" + input.documentType().label() + " " + input.documentId());
    }
  }

  /** 租户第一次建批次时把内置默认规则写进规则表（表里不预置任何一行）。 */
  private void seedDefaultRulesIfEmpty(UUID tenantId) {
    var count = sql.fetchOne("select count(*) from finance_bank_recon_rule").get(0, Long.class);
    if (count != null && count > 0) return;
    var now = nowUtc();
    for (var rule : DEFAULT_RULES) {
      sql.execute(
          "insert into finance_bank_recon_rule(id,tenant_id,rule_code,rule_name,rule_type,"
              + " priority,enabled,date_window_days,amount_tolerance,counterparty_required,"
              + " builtin,note,created_at,updated_at)"
              + " values (?,?,?,?,?,?,true,?,?,?,true,?,?::timestamptz,?::timestamptz)"
              + " on conflict (tenant_id,rule_code) do nothing",
          UUID.randomUUID(),
          tenantId,
          rule.code(),
          rule.name(),
          rule.type().name(),
          rule.priority(),
          rule.windowDays(),
          rule.tolerance(),
          rule.counterpartyRequired(),
          rule.note(),
          now,
          now);
    }
  }

  /**
   * 我方账面余额（来自 12.7.1.1 的余额历史）。
   *
   * @param opening true = 取期初（不晚于期初日零点的最后一条）；false = 取期末。
   * @return 读不到 ⇒ **null**（不是 0）。
   */
  private BigDecimal bookBalanceAt(
      UUID tenantId, UUID accountId, LocalDate boundary, boolean opening) {
    var cutoff =
        opening
            ? boundary.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()
            : boundary.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime().minusNanos(1000);
    var row =
        sql.fetchOne(
            "select balance_amount from finance_fund_account_balance"
                + " where tenant_id = ? and account_id = ? and recorded_at <= ?::timestamptz"
                + " order by recorded_at desc, id desc limit 1",
            tenantId,
            accountId,
            cutoff);
    return row == null ? null : money(row.get(0, BigDecimal.class));
  }

  /** 刷新批次上的未匹配计数。 */
  private void refreshUnmatchedCounts(UUID tenantId, UUID sessionId) {
    var bank = countUnmatched(sessionId, "BANK", "OPEN");
    var system = countUnmatched(sessionId, "SYSTEM", "OPEN");
    sql.execute(
        "update finance_bank_recon_session set unmatched_bank_count = ?, unmatched_system_count = ?,"
            + " version = version + 1, updated_at = ?::timestamptz where id = ? and tenant_id = ?",
        bank,
        system,
        nowUtc(),
        sessionId,
        tenantId);
  }

  /** 追加一条事件（append-only）。 */
  private void appendEvent(
      UUID tenantId,
      UUID sessionId,
      String eventType,
      String summary,
      Map<String, Object> payload,
      UUID operatorUserId,
      OffsetDateTime occurredAt) {
    String payloadJson;
    try {
      payloadJson = json.writeValueAsString(payload == null ? Map.of() : payload);
    } catch (Exception e) {
      payloadJson = "{}";
    }
    sql.execute(
        "insert into finance_bank_recon_event(id,tenant_id,session_id,event_type,summary,payload,"
            + " operator_user_id,occurred_at,created_at)"
            + " values (?,?,?,?,?,?::jsonb,?,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        sessionId,
        eventType,
        summary,
        payloadJson,
        operatorUserId,
        occurredAt,
        nowUtc());
  }

  private int countStatementLines(UUID sessionId) {
    return sql.fetchOne(
            "select count(*) from finance_bank_recon_statement_line where session_id = ?", sessionId)
        .get(0, Integer.class);
  }

  private int countUnmatched(UUID sessionId, String side, String status) {
    var where = new StringBuilder(" where session_id = ?");
    var args = new ArrayList<Object>();
    args.add(sessionId);
    if (side != null) {
      where.append(" and side = ?");
      args.add(side);
    }
    if (status != null) {
      where.append(" and status = ?");
      args.add(status);
    }
    return sql.fetchOne("select count(*) from finance_bank_recon_unmatched" + where, args.toArray())
        .get(0, Integer.class);
  }

  private BigDecimal sumDirection(UUID sessionId, String table, String direction) {
    return money(
        sql.fetchOne(
                "select coalesce(sum(amount),0) from " + table
                    + " where session_id = ? and direction = ?",
                sessionId,
                direction)
            .get(0, BigDecimal.class));
  }

  private BigDecimal sumUnmatched(UUID sessionId, String side, String status, String direction) {
    return money(
        sql.fetchOne(
                "select coalesce(sum(amount),0) from finance_bank_recon_unmatched"
                    + " where session_id = ? and side = ? and status = ? and direction = ?",
                sessionId,
                side,
                status,
                direction)
            .get(0, BigDecimal.class));
  }

  // ================================================================ 内部：行 → 模型

  static FinanceBankReconSessionRow sessionRow(Record r) {
    var status = FinanceBankReconStatus.valueOf(r.get("status", String.class));
    return new FinanceBankReconSessionRow(
        r.get("id", UUID.class),
        r.get("recon_no", String.class),
        r.get("account_id", UUID.class),
        r.get("account_no", String.class),
        r.get("account_name", String.class),
        r.get("currency", String.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("statement_opening_balance", BigDecimal.class),
        r.get("statement_closing_balance", BigDecimal.class),
        r.get("book_opening_balance", BigDecimal.class),
        r.get("book_closing_balance", BigDecimal.class),
        status.name(),
        status.label(),
        r.get("source_system", String.class),
        r.get("statement_line_count", Integer.class),
        r.get("system_line_count", Integer.class),
        r.get("matched_count", Integer.class),
        r.get("unmatched_bank_count", Integer.class),
        r.get("unmatched_system_count", Integer.class),
        r.get("reconciled_by", UUID.class),
        r.get("reconciled_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("version", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  static FinanceBankReconStatementLineRow statementLineRow(Record r) {
    var direction = FinanceBankReconDirection.valueOf(r.get("direction", String.class));
    return new FinanceBankReconStatementLineRow(
        r.get("id", UUID.class),
        r.get("session_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        direction.name(),
        direction.label(),
        r.get("amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("bank_reference", String.class),
        r.get("external_reference", String.class),
        r.get("counterparty", String.class),
        r.get("memo", String.class),
        r.get("import_batch", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  static FinanceBankReconSystemLineRow systemLineRow(Record r) {
    var direction = FinanceBankReconDirection.valueOf(r.get("direction", String.class));
    var sourceType = FinanceBankReconSourceType.valueOf(r.get("source_type", String.class));
    return new FinanceBankReconSystemLineRow(
        r.get("id", UUID.class),
        r.get("session_id", UUID.class),
        sourceType.name(),
        sourceType.label(),
        r.get("source_id", UUID.class),
        r.get("source_no", String.class),
        r.get("source_label", String.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("occurred_precision", String.class),
        direction.name(),
        direction.label(),
        r.get("amount", BigDecimal.class),
        r.get("raw_amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("external_reference", String.class),
        r.get("counterparty", String.class),
        r.get("memo", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  static FinanceBankReconMatchRow matchRow(Record r) {
    var ruleType = FinanceBankReconRuleType.valueOf(r.get("rule_type", String.class));
    var direction = FinanceBankReconDirection.valueOf(r.get("direction", String.class));
    return new FinanceBankReconMatchRow(
        r.get("id", UUID.class),
        r.get("session_id", UUID.class),
        r.get("statement_line_id", UUID.class),
        r.get("system_line_id", UUID.class),
        r.get("rule_code", String.class),
        ruleType.name(),
        ruleType.label(),
        r.get("match_basis", String.class),
        r.get("day_difference", Integer.class),
        r.get("amount", BigDecimal.class),
        r.get("currency", String.class),
        direction.name(),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("external_reference", String.class),
        r.get("counterparty", String.class),
        r.get("matched_by", UUID.class),
        r.get("matched_at", OffsetDateTime.class));
  }

  static FinanceBankReconUnmatchedRow unmatchedRow(Record r) {
    var side = FinanceBankReconSide.valueOf(r.get("side", String.class));
    var status = FinanceBankReconUnmatchedStatus.valueOf(r.get("status", String.class));
    var direction = FinanceBankReconDirection.valueOf(r.get("direction", String.class));
    var documentType = r.get("claimed_document_type", String.class);
    return new FinanceBankReconUnmatchedRow(
        r.get("id", UUID.class),
        r.get("session_id", UUID.class),
        side.name(),
        side.label(),
        r.get("statement_line_id", UUID.class),
        r.get("system_line_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        direction.name(),
        direction.label(),
        r.get("amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("counterparty", String.class),
        r.get("external_reference", String.class),
        r.get("memo", String.class),
        status.name(),
        status.label(),
        documentType,
        documentType == null
            ? null
            : FinanceBankReconDocumentType.valueOf(documentType).label(),
        r.get("claimed_document_id", UUID.class),
        r.get("claimed_document_no", String.class),
        r.get("claimed_by", UUID.class),
        r.get("claimed_at", OffsetDateTime.class),
        r.get("claim_reason", String.class),
        r.get("exclude_reason", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  static FinanceBankReconClaimRow claimRow(Record r) {
    var action = FinanceBankReconClaimAction.valueOf(r.get("action", String.class));
    var documentType = r.get("claimed_document_type", String.class);
    return new FinanceBankReconClaimRow(
        r.get("id", UUID.class),
        r.get("session_id", UUID.class),
        r.get("unmatched_id", UUID.class),
        action.name(),
        action.label(),
        documentType,
        documentType == null
            ? null
            : FinanceBankReconDocumentType.valueOf(documentType).label(),
        r.get("claimed_document_id", UUID.class),
        r.get("claimed_document_no", String.class),
        r.get("reason", String.class),
        r.get("claimed_by", UUID.class),
        r.get("claimed_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  static FinanceBankReconRuleRow ruleRow(Record r) {
    var ruleType = FinanceBankReconRuleType.valueOf(r.get("rule_type", String.class));
    return new FinanceBankReconRuleRow(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_name", String.class),
        ruleType.name(),
        ruleType.label(),
        r.get("priority", Integer.class),
        r.get("enabled", Boolean.class),
        r.get("date_window_days", Integer.class),
        r.get("amount_tolerance", BigDecimal.class),
        r.get("counterparty_required", Boolean.class),
        r.get("builtin", Boolean.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  static FinanceBankReconEventRow eventRow(Record r) {
    var payload = r.get("payload", String.class);
    return new FinanceBankReconEventRow(
        r.get("id", UUID.class),
        r.get("session_id", UUID.class),
        r.get("event_type", String.class),
        r.get("summary", String.class),
        payload,
        r.get("operator_user_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 内部：解析

  /** 解析出来的一行（键是**规范化**后的列名）。 */
  private record ParsedRow(int index, Map<String, String> values) {
    String value(String key) {
      var v = values.get(key);
      return v == null ? "" : v;
    }
  }

  /** CSV 必须有的列（规范化后：小写 + 去掉下划线与连字符）。 */
  private static final List<String> REQUIRED_COLUMNS =
      List.of("occurredat", "direction", "amount", "currency", "bankreference");

  /** CSV 可选列。 */
  private static final List<String> OPTIONAL_COLUMNS =
      List.of("externalreference", "counterparty", "memo");

  /**
   * 解析 CSV（**必须带表头**）。
   *
   * <p>⚠️ 为什么要表头：没有表头就只能靠列序猜，猜错一次就是**整批流水挂到错误的字段上**
   * （金额列变成日期列），而且**不会报错**。有表头 ⇒ 列名对不上直接拒收。
   */
  private List<ParsedRow> parseCsv(String content) {
    var lines = splitCsvLines(content);
    if (lines.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "流水文件是空的");
    }
    var header = splitCsvLine(lines.get(0));
    var index = new LinkedHashMap<String, Integer>();
    for (var i = 0; i < header.size(); i++) {
      index.putIfAbsent(normalizeColumn(header.get(i)), i);
    }
    for (var required : REQUIRED_COLUMNS) {
      if (!index.containsKey(required)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "CSV 表头缺少必需的列：" + required + "（必需的列：" + String.join("、", REQUIRED_COLUMNS)
                + "；可选的列：" + String.join("、", OPTIONAL_COLUMNS) + "）");
      }
    }

    var rows = new ArrayList<ParsedRow>();
    for (var i = 1; i < lines.size(); i++) {
      var line = lines.get(i);
      if (line.isBlank()) continue;
      var cells = splitCsvLine(line);
      var values = new LinkedHashMap<String, String>();
      for (var column : REQUIRED_COLUMNS) {
        values.put(column, cell(cells, index.get(column)));
      }
      for (var column : OPTIONAL_COLUMNS) {
        var at = index.get(column);
        values.put(column, at == null ? "" : cell(cells, at));
      }
      rows.add(new ParsedRow(i + 1, values));
    }
    return rows;
  }

  /** 解析 JSON 数组。 */
  private List<ParsedRow> parseJson(String content) {
    JsonNode root;
    try {
      root = json.readTree(content);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON 解析失败：" + e.getMessage());
    }
    if (root == null || !root.isArray()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON 流水文件必须是数组");
    }
    var rows = new ArrayList<ParsedRow>();
    var index = 0;
    for (var node : root) {
      index++;
      if (!node.isObject()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "JSON 数组第 " + index + " 项不是对象");
      }
      var values = new LinkedHashMap<String, String>();
      // ⚠️ JSON 的键也要**规范化**（occurredAt → occurredat、bankReference → bankreference）：
      //    否则 camelCase 的账单文件会因为「列名找不到」而整行被跳过。
      var fields = node.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        values.put(normalizeColumn(field.getKey()), field.getValue().asText());
      }
      for (var column : REQUIRED_COLUMNS) {
        values.putIfAbsent(column, "");
      }
      for (var column : OPTIONAL_COLUMNS) {
        values.putIfAbsent(column, "");
      }
      rows.add(new ParsedRow(index, values));
    }
    return rows;
  }

  private static String cell(List<String> cells, int index) {
    return index < cells.size() ? cells.get(index) : "";
  }

  /** 列名规范化：小写 + 去掉下划线 / 连字符 / 空格。 */
  private static String normalizeColumn(String value) {
    if (value == null) return "";
    var sb = new StringBuilder();
    for (char c : value.trim().toLowerCase(Locale.ROOT).toCharArray()) {
      if (Character.isLetterOrDigit(c)) sb.append(c);
    }
    return sb.toString();
  }

  /** CSV 行拆分（支持引号包裹与字段内的换行）。 */
  private static List<String> splitCsvLines(String content) {
    var lines = new ArrayList<String>();
    var current = new StringBuilder();
    var inQuotes = false;
    for (var i = 0; i < content.length(); i++) {
      var c = content.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
            current.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          current.append(c);
        }
        continue;
      }
      if (c == '"') {
        inQuotes = true;
      } else if (c == '\n') {
        lines.add(current.toString());
        current.setLength(0);
      } else if (c != '\r') {
        current.append(c);
      }
    }
    if (current.length() > 0) lines.add(current.toString());
    return lines;
  }

  /** CSV 行内字段拆分（引号内的逗号不算分隔符）。 */
  private static List<String> splitCsvLine(String line) {
    var cells = new ArrayList<String>();
    var current = new StringBuilder();
    var inQuotes = false;
    for (var i = 0; i < line.length(); i++) {
      var c = line.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            current.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          current.append(c);
        }
        continue;
      }
      if (c == '"') {
        inQuotes = true;
      } else if (c == ',') {
        cells.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    cells.add(current.toString());
    return cells;
  }

  /**
   * 解析事实时刻。
   *
   * <p>⚠️ <b>没有时区偏移的时间串直接拒收</b>：{@code Timestamp.valueOf(LocalDateTime)} 在东八区
   * 会静默偏 −28800 秒 —— 到账时间偏 8 小时就是错账，而且不会抛异常。
   */
  private static OffsetDateTime parseOccurredAt(String raw) {
    var value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("事实时刻不能为空");
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (DateTimeParseException ignored) {
      // 落到下面统一报错。
    }
    throw new IllegalArgumentException(
        "事实时刻必须带时区偏移（如 2026-10-01T08:30:00+08:00 或 2026-10-01T00:30:00Z）：" + value);
  }

  /** 解析方向（IN / OUT，兼容中文与 CREDIT / DEBIT）。 */
  private static FinanceBankReconDirection parseDirection(String raw) {
    var value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    return switch (value) {
      case "IN", "收入", "CREDIT", "CR" -> FinanceBankReconDirection.IN;
      case "OUT", "支出", "DEBIT", "DR" -> FinanceBankReconDirection.OUT;
      default ->
          throw new IllegalArgumentException(
              "方向只能是 IN / OUT（收入 / 支出），实际读到：" + raw);
    };
  }

  private static void addWarning(List<String> warnings, int index, String reason) {
    if (warnings.size() < MAX_WARNINGS) {
      warnings.add("第 " + index + " 行：" + reason);
    }
  }

  /** 金额统一到 4 位小数（BigDecimal；**禁浮点**）。 */
  static BigDecimal money(BigDecimal amount) {
    if (amount == null) return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  /** 对账单号生成（BR- + 日期 + 短随机）。 */
  private String generatedReconNo() {
    return RECON_NO_PREFIX
        + LocalDate.now(ZoneOffset.UTC).toString().replace("-", "")
        + "-"
        + UUID.randomUUID().toString().substring(0, 8);
  }

  /** 当前时刻（截断到**微秒**：PG 的时间精度就是微秒，不截断写回值会差最后一位）。 */
  private static OffsetDateTime nowUtc() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }
}
