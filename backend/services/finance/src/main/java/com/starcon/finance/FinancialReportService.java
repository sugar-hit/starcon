package com.starcon.finance;

import static com.starcon.finance.FinancialReportModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.FinancialReportModels.FinanceReportBasis;
import com.starcon.finance.FinancialReportModels.FinanceReportCapability;
import com.starcon.finance.FinancialReportModels.FinancialReportCashFlowGroup;
import com.starcon.finance.FinancialReportModels.FinancialReportCashRole;
import com.starcon.finance.FinancialReportModels.FinanceReportCheck;
import com.starcon.finance.FinancialReportModels.FinanceReportCurrencyGroup;
import com.starcon.finance.FinancialReportModels.FinanceReportDownstreamGap;
import com.starcon.finance.FinancialReportModels.FinanceReportLedgerEntry;
import com.starcon.finance.FinancialReportModels.FinanceReportRow;
import com.starcon.finance.FinancialReportModels.FinancialReportSection;
import com.starcon.finance.FinancialReportModels.FinanceReportTemplateRow;
import com.starcon.finance.FinancialReportModels.FinancialReportType;
import com.starcon.finance.FinancialReportModels.FinanceReportTotals;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.5.2.2 财务报表。
 *
 * <p>蓝图 12-finance.md：「<b>生成资产负债表、利润表、现金流量表、科目余额表、明细账</b>；支持期间对比、导出、钻取到凭证。输入：已过账凭证；期间范围；报表模板。输出：<b>报表 + 钻取路径 + 导出文件</b>。关系：上游 = 12.5.2.1 凭证管理；下游 = 17 审计、对外报送。<b>报表数字必须能钻到凭证</b>，"报表与明细对不上"是财务系统的致命问题。」
 *
 * <p><b>本模块的五条设计主线</b>：
 *
 * <ol>
 *   <li><b>只读已过账的账，一行都不改。</b>本模块**不拥有**任何账务表：凭证、分录行、
 *       会计期间全部来自 12.5.2.1（V357），本模块只做 jOOQ 显式 SQL 的<b>只读聚合</b>。
 *       迁移 V360 只建自己的两张表（报表模板 + 导出留痕）。
 *   <li><b>三条口径写死在代码与能力声明里</b>，并且**随报表一起返回**（页面原样显示）：
 *       <ul>
 *         <li><b>草稿绝不进报表</b>：只取 {@code status IN ('POSTED','REVERSED')}；
 *             DRAFT / VOID 都不是账（本期排除的草稿凭证数会写进结果里）。</li>
 *         <li><b>红冲按「原凭证 + 反向凭证」净额处理</b>，不是简单排除。
 *             12.5.2.1 的红冲 = 新建一张反向凭证（借贷对调、金额相同）+ 原凭证标 REVERSED，
 *             而 DB 层 {@code finance_voucher_reversed_ck} 钉死「REVERSED ⇒ 必须指向那张红冲凭证」
 *             且有 FK ⇒ 原凭证与反向凭证**必然成对**。两者同时纳入 ⇒ 同一科目上净额自动抵消为 0；
 *             「原凭证在上期、红冲凭证在本期」时本期净额同样正确抵消。</li>
 *         <li><b>多币种绝不相加</b>：所有汇总按 {@code finance_voucher.currency} 分组，
 *             结果结构里**没有**「本位币金额」字段 —— 让折算在结构上做不到。
 *             本模块不做本位币折算（折算要汇率，汇率不可得时应当按币种分组呈现）。</li>
 *       </ul>
 *   <li><b>报表数字必须能钻到凭证</b>：每一个报表行、每一个合计项都带一条
 *       {@code drillPath}，那是**真实可走的端点路径**（不是文案）；钻取端点返回构成该行的
 *       **真实凭证 + 分录行**（含凭证号、行号、金额）。
 *   <li><b>科目是软引用，绝不假装核对过</b>：分类口径取自 12.5.1.1 的
 *       {@code finance_account_subject}（category + balance_direction）的
 *       <b>LEFT JOIN</b>；科目没登记 ⇒ 归「未分类」段落、<b>不并入合计</b>、如实提示。
 *       本模块**不建**指向科目表的外键，**不声称**校验过科目存在性。
 *   <li><b>报表与明细对得上，是机器证明的</b>：每张报表都带 {@link FinanceReportCheck}：
 *       <ul>
 *         <li><b>借贷平衡</b>：本期借方合计 = 本期贷方合计（差额必须 0）；</li>
 *         <li><b>会计恒等式</b>（资产负债表 / 科目余额表）：
 *             资产 = 负债 + 所有者权益 + 累计未结转损益（差额必须 0）。</li>
 *       </ul>
 *       差额非 0 时**如实报出差额并说明可能原因**，绝不悄悄抹平。
 * </ol>
 */
@Service
public class FinancialReportService {

  /** 明细账一次最多返回多少笔（防止一次拉爆内存）。 */
  static final int MAX_LEDGER_ENTRIES = 5000;

  /** 钻取一次最多返回多少行。 */
  static final int MAX_DRILL_ENTRIES = 5000;

  /** 导出一次最多多少行。 */
  static final int MAX_EXPORT_ROWS = 20000;

  /** 零金额（金额一律 BigDecimal，不用 double）。 */
  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);

  private final DSLContext sql;
  private final ObjectMapper json;

  public FinancialReportService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ================================================================ 内部结构

  /** 期间范围（from ~ to 的期间序列）。 */
  private record PeriodWindow(
      UUID fromId, UUID toId, String fromCode, String toCode, LocalDate start, LocalDate end) {

    String label() {
      return fromCode.equals(toCode) ? fromCode : fromCode + " ~ " + toCode;
    }
  }

  /** 一个（币种, 科目）的聚合结果。 */
  private static final class Agg {
    final String currency;
    final String subjectCode;
    String subjectName = "";
    String category = "";
    String balanceDirection = "";
    BigDecimal openingDebit = ZERO;
    BigDecimal openingCredit = ZERO;
    BigDecimal periodDebit = ZERO;
    BigDecimal periodCredit = ZERO;
    long voucherCount;
    long lineCount;

    Agg(String currency, String subjectCode) {
      this.currency = currency;
      this.subjectCode = subjectCode;
    }

    void addPeriod(BigDecimal debit, BigDecimal credit, long vouchers, long lines) {
      this.periodDebit = this.periodDebit.add(debit);
      this.periodCredit = this.periodCredit.add(credit);
      this.voucherCount += vouchers;
      this.lineCount += lines;
    }

    void addOpening(BigDecimal debit, BigDecimal credit) {
      this.openingDebit = this.openingDebit.add(debit);
      this.openingCredit = this.openingCredit.add(credit);
    }
  }

  /** 报表模板（读出来的那一份）。 */
  private record TemplateView(
      String reportType,
      String subjectCode,
      String lineCode,
      String lineName,
      String section,
      String cashFlowGroup,
      String cashRole,
      int sortOrder) {}

  /** 一行报表的累加桶（一个报表行可能归并多个科目）。 */
  private static final class Bucket {
    String section = "";
    String sectionLabel = "";
    String lineCode = "";
    String lineName = "";
    String subjectCode;
    String subjectName;
    String category = "";
    String balanceDirection = "";
    BigDecimal openingDebit = ZERO;
    BigDecimal openingCredit = ZERO;
    BigDecimal periodDebit = ZERO;
    BigDecimal periodCredit = ZERO;
    long voucherCount;
    long lineCount;
    int subjectCount;
  }

  /** 一条分录行（钻取 / 明细账用）。 */
  private record LineRow(
      UUID voucherId,
      String voucherNo,
      LocalDate voucherDate,
      String periodCode,
      String voucherStatus,
      UUID lineId,
      int lineNo,
      String lineType,
      String subjectCode,
      String subjectName,
      String summary,
      BigDecimal amount,
      String currency) {}

  /** 现金流的一笔（按对方科目归类）。 */
  private record CashFlowItem(
      String currency, String group, BigDecimal inflow, BigDecimal outflow, List<LineRow> lines) {}

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**，外加**五张报表各自的口径**。
   */
  public FinanceReportCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceReportCapability>();
    var gaps = new ArrayList<FinanceReportDownstreamGap>();

    capabilities.add(
        new FinanceReportCapability(
            "12.5.2.2",
            "五张报表（资产负债表 / 利润表 / 现金流量表 / 科目余额表 / 明细账）",
            true,
            "V360 迁移 + FinancialReportService（jOOQ 显式 SQL 只读聚合）",
            "",
            "五张报表**全部交付**，蓝图无豁免：资产负债表、利润表、现金流量表、科目余额表、明细账；"
                + "支持**期间对比**（两段期间并排）、**导出**（CSV / JSON 真内容 + 一条留痕）、"
                + "**钻取到凭证**（每一行都带真实 drillPath）。"
                + "数据源唯一：12.5.2.1 的已过账凭证（finance_voucher / finance_voucher_line / "
                + "finance_voucher_period），本模块**一行都不改**。"));

    capabilities.add(
        new FinanceReportCapability(
            "12.5.2.2",
            "草稿绝不进报表",
            true,
            "取数条件 status IN ('POSTED','REVERSED')",
            "",
            "DRAFT（草稿）与 VOID（作废）都不是账，一律排除；"
                + "本期被排除的草稿凭证数会写进报表结果里（不藏着）。"));

    capabilities.add(
        new FinanceReportCapability(
            "12.5.2.2",
            "红冲按「原凭证 + 反向凭证」净额处理",
            true,
            "finance_voucher_reversed_ck + VoucherService.reverse 语义",
            "",
            "红冲**不是排除**：原凭证（REVERSED）与反向凭证（POSTED）**成对**纳入取数，"
                + "反向凭证借贷对调、金额相同 ⇒ 同一科目上净额自动抵消为 0。"
                + "「原凭证在上期、红冲凭证在本期」时，本期净额同样正确抵消。"
                + "本期已红冲的原凭证数写在结果里。"));

    capabilities.add(
        new FinanceReportCapability(
            "12.5.2.2",
            "多币种绝不相加",
            true,
            "按 finance_voucher.currency 分组（结果结构里没有「本位币金额」字段）",
            "",
            "所有汇总按币种分组返回，不同币种摆进不同分组、**不做任何折算**。"
                + "本位币折算需要汇率（catalog 只读端口），汇率不可得时应当按币种分组呈现 —— "
                + "本模块**不瞎折算**，结构上也没有折算字段。"));

    capabilities.add(
        new FinanceReportCapability(
            "12.5.2.2",
            "报表数字必须能钻到凭证",
            true,
            "每一行的 drillPath + GET /finance/financial-reports/drill",
            "",
            "**每一个报表行与每一个合计项**都带一条真实可走的钻取路径（不是文案）；"
                + "钻取端点返回构成该行的**真实凭证 + 分录行**（凭证号、行号、金额）。"
                + "明细账更是逐笔带凭证号与行号。"));

    capabilities.add(
        new FinanceReportCapability(
            "12.5.2.2",
            "报表与明细对得上（机器自检）",
            true,
            "FinanceReportCheck（借贷平衡 + 会计恒等式）",
            "",
            "每张报表都带自检：**本期借方合计 = 本期贷方合计**（差额必须 0）；"
                + "资产负债表另有 **资产 = 负债 + 所有者权益 + 累计未结转损益**。"
                + "差额非 0 时如实报出差额并说明可能原因（未登记科目 / 未结转损益），绝不抹平。"));

    capabilities.add(
        new FinanceReportCapability(
            "12.5.2.2",
            "报表期间对比（两段期间并排）",
            true,
            "periodFromId / periodToId + comparePeriodFromId / comparePeriodToId",
            "",
            "两段期间**并排**返回（本期行 + 对比期数值 + 变动额）；"
                + "对比期用**同一套口径**重算，绝不拿「上期快照」凑数。"));

    capabilities.add(
        new FinanceReportCapability(
            "12.5.2.2",
            "导出真内容 + 一条留痕",
            true,
            "GET /finance/financial-reports/export + finance_report_export_event（V360）",
            "",
            "CSV（带 UTF-8 BOM）/ JSON **真内容**（不是占位字节）；"
                + "每导一次写一条 append-only 留痕，带 **SHA-256** ⇒ 事后能证明导出的就是这一份。"));

    // ---- 如实标注的前置条件 ----

    capabilities.add(
        new FinanceReportCapability(
            "12.5.2.2",
            "现金流量表需要先配置「现金科目」",
            false,
            "finance_report_template.cash_role = 'CASH'",
            "12.5.2.2（本模块自身：租户在『报表模板』里配置）",
            "「哪个科目是现金科目」是**会计政策**：写死「1001/1002 就是现金」等于把用户的账写死。"
                + "⇒ 模板里把现金科目标记为 CASH、把对方科目归入活动类别后，现金流量表才出数；"
                + "未配置时返回**空 + 一句说清原因的话**，绝不虚构数据。"));

    capabilities.add(
        new FinanceReportCapability(
            "12.5.2.2",
            "科目分类口径依赖 12.5.1.1 科目表",
            true,
            "finance_account_subject.category / balance_direction（LEFT JOIN）",
            "",
            "分类口径取自 12.5.1.1 科目表的 **LEFT JOIN**：科目没登记 ⇒ 归「未分类」段落、"
                + "**不并入合计**、并在报表提示里点名。"
                + "🔴 本模块**不建**指向科目表的外键、**不声称**校验过科目存在性"
                + "（凭证行的科目是快照，手工凭证允许带科目表里没有的编码）。"));

    gaps.add(
        new FinanceReportDownstreamGap(
            "17 审计 / 对外报送",
            "审计工作流与对外报送流程",
            "identity / 外部报送系统",
            false,
            "下游",
            "本模块提供的是**报表 + 钻取路径 + 导出文件 + 一条 append-only 导出留痕（含 SHA-256）**；"
                + "审计工作流与对外报送流程本身不属本模块。"));

    var bases = new ArrayList<FinanceReportBasis>();
    for (var type : FinancialReportType.values()) {
      bases.add(basisOf(type));
    }
    return new FinanceReportCapabilities(capabilities, gaps, bases);
  }

  /** 五张报表**各自**的口径说明。 */
  private FinanceReportBasis basisOf(FinancialReportType type) {
    var draft = "只取已过账（POSTED）与已红冲（REVERSED）的凭证；草稿（DRAFT）与作废（VOID）不进报表。";
    var reversal =
        "红冲按「原凭证 + 反向凭证」净额处理：反向凭证借贷对调、金额相同，"
            + "与原凭证一并纳入后同一科目上净额自动抵消为 0（不是把红冲凭证排除掉）。";
    var currency = "按币种分组，不做本位币折算，不同币种绝不相加。";
    String detail;
    switch (type) {
      case BALANCE_SHEET -> {
        detail =
            "期末余额 = 期初余额 + 本期发生额；期初余额取**本期范围之前**所有已过账凭证的累计数。"
                + "行按科目类别归入资产 / 负债 / 所有者权益 / 损益结转四段；"
                + "损益结转段是**累计未结转的损益**（系统不自动结转），"
                + "自检「资产 = 负债 + 所有者权益 + 累计未结转损益」的差额必须为 0。";
      }
      case INCOME_STATEMENT -> {
        detail =
            "本期发生额：收入取贷方净额、费用取借方净额（按科目余额方向判定收入 / 费用）；"
                + "本期损益 = 收入合计 − 成本费用合计。区间是**本期**（不是累计），"
                + "与对比期逐行并排给出变动额。";
      }
      case CASH_FLOW -> {
        detail =
            "**需先在『报表模板』里把现金科目标记为 CASH、把对方科目归入经营 / 投资 / 筹资活动。**"
                + "口径：只统计**含现金科目**的凭证，按**对方（非现金）分录行**归类 —— "
                + "对方在贷方 ⇒ 该组现金流入，对方在借方 ⇒ 该组现金流出；"
                + "现金科目之间的转账（整张凭证都是现金科目）不进现金流量表。"
                + "未配置现金科目时返回空并说明原因，不虚构数据。";
      }
      case TRIAL_BALANCE -> {
        detail =
            "一行一个科目（模板可以把多个科目归并成一行）：期初借 / 期初贷 / 本期借 / 本期贷 / "
                + "期末借 / 期末贷 / 期末余额（按科目余额方向取正负）。"
                + "自检：期末借方合计 = 期末贷方合计。";
      }
      default -> {
        detail =
            "明细账按**一个科目**逐笔展开：日期、期间、凭证号、分录行号、摘要、对方科目、"
                + "借方额、贷方额、逐笔余额。逐笔余额从期初余额起算，"
                + "与上期的期末余额严丝合缝（期初 = 上期期末）。";
      }
    }
    return new FinanceReportBasis(type.name(), type.label(), draft, reversal, currency, detail);
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinanceReportOptions options() {
    var periods =
        sql.fetch(
                "select id, period_code, period_start, period_end, status, base_currency"
                    + " from finance_voucher_period order by period_start desc, period_code")
            .map(
                r ->
                    new FinanceReportPeriodRef(
                        r.get("id", UUID.class),
                        r.get("period_code", String.class),
                        r.get("period_start", LocalDate.class),
                        r.get("period_end", LocalDate.class),
                        r.get("status", String.class),
                        r.get("base_currency", String.class)));

    var currencies =
        sql.fetch("select distinct currency from finance_voucher order by currency")
            .map(r -> r.get(0, String.class));

    // 科目候选：科目表登记的 + 凭证行里出现过的（软引用 ⇒ 两边都要能看到）。
    var subjects = new LinkedHashMap<String, FinanceReportSubjectRef>();
    for (var r :
        sql.fetch(
            "select subject_code, subject_name, category, balance_direction from finance_account_subject"
                + " order by subject_code")) {
      subjects.put(
          r.get("subject_code", String.class),
          new FinanceReportSubjectRef(
              r.get("subject_code", String.class),
              r.get("subject_name", String.class),
              nullToEmpty(r.get("category", String.class)),
              nullToEmpty(r.get("balance_direction", String.class))));
    }
    for (var r :
        sql.fetch(
            "select subject_code,"
                + " (array_agg(subject_name order by created_at desc))[1] as subject_name"
                + " from finance_voucher_line group by subject_code order by subject_code")) {
      var code = r.get("subject_code", String.class);
      if (subjects.containsKey(code)) continue;
      subjects.put(
          code,
          new FinanceReportSubjectRef(
              code, nullToEmpty(r.get("subject_name", String.class)), "", ""));
    }

    var templates = templateRows(null);
    var reportTypes = Arrays.stream(FinancialReportType.values()).map(Enum::name).toList();
    return new FinanceReportOptions(
        periods, currencies, List.copyOf(subjects.values()), templates, reportTypes);
  }

  // ================================================================ 报表模板

  /** 报表模板列表（reportType 为空 ⇒ 全部）。 */
  public List<FinanceReportTemplateRow> templateRows(String reportType) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (reportType != null && !reportType.isBlank()) {
      where.add("report_type = ?");
      binds.add(reportType);
    }
    var clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    return sql.fetch(
            "select * from finance_report_template"
                + clause
                + " order by report_type, sort_order, line_code, subject_code",
            binds.toArray())
        .map(this::templateRow);
  }

  /** 登记一条报表模板（系统不预置任何一行，行由租户维护）。 */
  @Transactional
  public FinanceReportTemplateRow createTemplate(
      FinanceReportTemplateInput input, AccessService.Access actor) {
    if (input == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    var type = parseReportType(input.reportType());
    var subjectCode = requireText(input.subjectCode(), "科目编码");
    var lineCode = requireText(input.lineCode(), "报表行编码");
    var cashRole = parseCashRole(input.cashRole());
    var cashGroup = parseCashGroup(input.cashFlowGroup());
    if (type == FinancialReportType.CASH_FLOW && cashRole.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "现金流量表的模板行必须说清现金角色：CASH（现金科目）或 NON_CASH（对方科目）");
    }
    if (cashRole.equals("NON_CASH") && cashGroup.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "对方科目（NON_CASH）必须归入经营 / 投资 / 筹资活动之一");
    }
    if (!cashRole.isEmpty() && type != FinancialReportType.CASH_FLOW) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "现金角色只对现金流量表有意义，其它报表类型请留空");
    }
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      sql.execute(
          "insert into finance_report_template(id,tenant_id,report_type,subject_code,subject_name,"
              + "line_code,line_name,section,cash_flow_group,cash_role,sort_order,enabled,note,"
              + "version,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,0,?::timestamptz,?::timestamptz)",
          id,
          actor.tenantId(),
          type.name(),
          subjectCode,
          input.subjectName() == null ? "" : input.subjectName().trim(),
          lineCode,
          input.lineName() == null ? "" : input.lineName().trim(),
          input.section() == null ? "" : input.section().trim(),
          cashGroup,
          cashRole,
          input.sortOrder() == null ? 0 : input.sortOrder(),
          input.enabled() == null || input.enabled(),
          input.note() == null ? "" : input.note().trim(),
          now,
          now);
    } catch (org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该科目在这张报表里已经有一条模板行了（一个科目只能归一条行）：" + subjectCode);
    }
    return templateRow(
        sql.fetchOne(
            "select * from finance_report_template where id = ? and tenant_id = ?",
            id,
            actor.tenantId()));
  }

  /** 修改一条报表模板（乐观并发）。 */
  @Transactional
  public FinanceReportTemplateRow updateTemplate(
      UUID id, FinanceReportTemplateInput input, AccessService.Access actor) {
    if (input == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    var existing =
        sql.fetchOne(
            "select * from finance_report_template where id = ? and tenant_id = ?", id, actor.tenantId());
    if (existing == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报表模板不存在");
    }
    var version = existing.get("version", Long.class);
    if (input.expectedVersion() != null && input.expectedVersion().longValue() != version) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "报表模板已被他人修改（当前版本 " + version + "，请刷新后重试）");
    }
    var type = parseReportType(input.reportType());
    var subjectCode = requireText(input.subjectCode(), "科目编码");
    var lineCode = requireText(input.lineCode(), "报表行编码");
    var cashRole = parseCashRole(input.cashRole());
    var cashGroup = parseCashGroup(input.cashFlowGroup());
    if (type == FinancialReportType.CASH_FLOW && cashRole.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "现金流量表的模板行必须说清现金角色：CASH（现金科目）或 NON_CASH（对方科目）");
    }
    if (cashRole.equals("NON_CASH") && cashGroup.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "对方科目（NON_CASH）必须归入经营 / 投资 / 筹资活动之一");
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      sql.execute(
          "update finance_report_template set report_type=?, subject_code=?, subject_name=?,"
              + " line_code=?, line_name=?, section=?, cash_flow_group=?, cash_role=?, sort_order=?,"
              + " enabled=?, note=?, version=version+1, updated_at=?::timestamptz"
              + " where id=? and tenant_id=?",
          type.name(),
          subjectCode,
          input.subjectName() == null ? "" : input.subjectName().trim(),
          lineCode,
          input.lineName() == null ? "" : input.lineName().trim(),
          input.section() == null ? "" : input.section().trim(),
          cashGroup,
          cashRole,
          input.sortOrder() == null ? 0 : input.sortOrder(),
          input.enabled() == null || input.enabled(),
          input.note() == null ? "" : input.note().trim(),
          now,
          id,
          actor.tenantId());
    } catch (org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该科目在这张报表里已经有另一条模板行了：" + subjectCode);
    }
    return templateRow(
        sql.fetchOne(
            "select * from finance_report_template where id = ? and tenant_id = ?",
            id,
            actor.tenantId()));
  }

  /** 停用一条报表模板（**不删**：历史报表口径要能复现）。 */
  @Transactional
  public FinanceReportTemplateRow disableTemplate(UUID id, AccessService.Access actor) {
    var existing =
        sql.fetchOne(
            "select * from finance_report_template where id = ? and tenant_id = ?", id, actor.tenantId());
    if (existing == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报表模板不存在");
    }
    sql.execute(
        "update finance_report_template set enabled=false, version=version+1, updated_at=?::timestamptz"
            + " where id=? and tenant_id=?",
        OffsetDateTime.now(ZoneOffset.UTC),
        id,
        actor.tenantId());
    return templateRow(
        sql.fetchOne(
            "select * from finance_report_template where id = ? and tenant_id = ?",
            id,
            actor.tenantId()));
  }

  private FinanceReportTemplateRow templateRow(org.jooq.Record r) {
    var type = FinancialReportType.valueOf(r.get("report_type", String.class));
    var group = r.get("cash_flow_group", String.class);
    var role = r.get("cash_role", String.class);
    return new FinanceReportTemplateRow(
        r.get("id", UUID.class),
        type.name(),
        type.label(),
        r.get("subject_code", String.class),
        nullToEmpty(r.get("subject_name", String.class)),
        r.get("line_code", String.class),
        nullToEmpty(r.get("line_name", String.class)),
        nullToEmpty(r.get("section", String.class)),
        nullToEmpty(r.get("section", String.class)),
        nullToEmpty(group),
        group.isEmpty() ? "" : FinancialReportCashFlowGroup.valueOf(group).label(),
        nullToEmpty(role),
        role.isEmpty() ? "" : FinancialReportCashRole.valueOf(role).label(),
        r.get("sort_order", Integer.class),
        r.get("enabled", Boolean.class),
        nullToEmpty(r.get("note", String.class)),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 报表

  /**
   * 生成一张报表。
   *
   * @param currency 币种范围（空 ⇒ 全部币种分组）
   * @param subjectCode 明细账专用（其它报表忽略）
   */
  public FinanceReportResult report(
      FinancialReportType type,
      UUID periodFromId,
      UUID periodToId,
      UUID comparePeriodFromId,
      UUID comparePeriodToId,
      String currency,
      String subjectCode) {
    var window = requireWindow(periodFromId, periodToId);
    var compare =
        comparePeriodFromId != null && comparePeriodToId != null
            ? requireWindow(comparePeriodFromId, comparePeriodToId)
            : null;
    var currencyFilter = currency == null || currency.isBlank() ? null : currency.trim().toUpperCase(Locale.ROOT);

    var current = aggregates(window, currencyFilter);
    var comparison = compare == null ? Map.<String, Agg>of() : aggregates(compare, currencyFilter);
    var templates = templateViews(type);

    var draftExcluded = draftCount(window, currencyFilter);
    var reversedCount = reversedCount(window, currencyFilter);

    var notice = new StringBuilder();
    notice
        .append("取数：只含已过账（POSTED）与已红冲（REVERSED）的凭证；草稿 ")
        .append(draftExcluded)
        .append(" 张、作废凭证均不进报表。");
    notice.append("红冲按原凭证与反向凭证净额抵消（本期已红冲 ").append(reversedCount).append(" 张）。");
    notice.append("按币种分组，不做本位币折算。");

    if (type == FinancialReportType.CASH_FLOW && countCashSubjects(templates) == 0) {
      notice
          .append(
              " ⚠️ 现金流量表**未配置现金科目**：请在『报表模板』里把现金科目标记为 CASH、"
                  + "把对方科目归入经营 / 投资 / 筹资活动；未配置时本表出数 0 行（不虚构数据）。");
    }

    var groups = new ArrayList<FinanceReportGrouping>();
    var currencies = currenciesOf(current);
    if (currencies.isEmpty()) {
      currencies = List.of(currencyFilter == null ? "" : currencyFilter);
    }
    for (var currencyKey : currencies) {
      var currentAggs = byCurrency(current, currencyKey);
      var comparisonAggs = byCurrency(comparison, currencyKey);
      switch (type) {
        case BALANCE_SHEET ->
            groups.add(balanceSheet(currencyKey, currentAggs, comparisonAggs, templates, window, compare));
        case INCOME_STATEMENT ->
            groups.add(incomeStatement(currencyKey, currentAggs, comparisonAggs, templates, window, compare));
        case CASH_FLOW -> groups.add(cashFlow(currencyKey, window, currencyFilter, templates));
        case TRIAL_BALANCE ->
            groups.add(trialBalance(currencyKey, currentAggs, comparisonAggs, templates, window, compare));
        default ->
            groups.add(
                generalLedger(
                    currencyKey, currentAggs, comparisonAggs, templates, window, compare, subjectCode, notice));
      }
    }

    var result = new ArrayList<FinanceReportCurrencyGroup>();
    for (var grouping : groups) {
      result.add(grouping.toGroup());
    }
    long voucherCount = 0;
    long lineCount = 0;
    for (var group : result) {
      voucherCount += group.totals().voucherCount();
      lineCount += group.totals().lineCount();
    }

    return new FinanceReportResult(
        type.name(),
        type.label(),
        window.fromCode(),
        window.toCode(),
        window.label(),
        compare == null ? "" : compare.fromCode(),
        compare == null ? "" : compare.toCode(),
        compare == null ? "" : compare.label(),
        currencyFilter == null ? "ALL" : currencyFilter,
        result,
        notice.toString(),
        voucherCount,
        lineCount,
        draftExcluded,
        reversedCount);
  }

  /** 报表分组的中间产物。 */
  private record FinanceReportGrouping(
      String currency,
      List<FinanceReportRow> rows,
      List<FinanceReportLedgerEntry> ledgerEntries,
      FinanceReportTotals totals,
      FinanceReportTotals comparisonTotals,
      List<FinanceReportCheck> checks) {

    FinanceReportCurrencyGroup toGroup() {
      return new FinanceReportCurrencyGroup(
          currency, rows, ledgerEntries, totals, comparisonTotals, checks);
    }
  }

  // ---------------------------------------------------------------- 资产负债表

  private FinanceReportGrouping balanceSheet(
      String currency,
      Map<String, Agg> current,
      Map<String, Agg> comparison,
      Map<String, TemplateView> templates,
      PeriodWindow window,
      PeriodWindow compare) {
    var buckets = bucketize(current, templates, FinancialReportType.BALANCE_SHEET, null);
    var rows = new ArrayList<FinanceReportRow>();
    var assetRows = new ArrayList<FinanceReportRow>();
    var liabilityRows = new ArrayList<FinanceReportRow>();
    var equityRows = new ArrayList<FinanceReportRow>();
    var retainedRows = new ArrayList<FinanceReportRow>();
    var unclassifiedRows = new ArrayList<FinanceReportRow>();
    for (var bucket : buckets) {
      var row =
          toRow(
              bucket,
              currency,
              comparisonBucket(comparison, templates, FinancialReportType.BALANCE_SHEET, bucket.lineCode),
              FinancialReportType.BALANCE_SHEET,
              window,
              compare,
              false);
      rows.add(row);
      switch (bucket.section) {
        case "ASSET" -> assetRows.add(row);
        case "LIABILITY" -> liabilityRows.add(row);
        case "EQUITY" -> equityRows.add(row);
        case "RETAINED" -> retainedRows.add(row);
        default -> unclassifiedRows.add(row);
      }
    }
    var asset = sumClosing(assetRows);
    var liability = sumClosing(liabilityRows);
    var equity = sumClosing(equityRows);
    var retained = sumClosing(retainedRows);
    var unclassified = sumClosing(unclassifiedRows);
    rows.add(
        totalRow(
            FinancialReportType.BALANCE_SHEET,
            "资产合计",
            "ASSET",
            "资产",
            "ASSET",
            currency,
            assetRows,
            null,
            null,
            window,
            compare));
    rows.add(
        totalRow(
            FinancialReportType.BALANCE_SHEET,
            "负债合计",
            "LIABILITY",
            "负债",
            "LIABILITY",
            currency,
            liabilityRows,
            null,
            null,
            window,
            compare));
    rows.add(
        totalRow(
            FinancialReportType.BALANCE_SHEET,
            "所有者权益合计",
            "EQUITY",
            "所有者权益",
            "EQUITY",
            currency,
            equityRows,
            null,
            null,
            window,
            compare));
    rows.add(
        totalRow(
            FinancialReportType.BALANCE_SHEET,
            "损益结转（累计未结转）",
            "RETAINED",
            "损益结转",
            "RETAINED",
            currency,
            retainedRows,
            null,
            null,
            window,
            compare));
    if (!unclassifiedRows.isEmpty()) {
      rows.add(
          totalRow(
              FinancialReportType.BALANCE_SHEET,
              "未分类科目（未在科目表登记，不并入合计）",
              "UNCLASSIFIED",
              "未分类",
              "UNCLASSIFIED",
              currency,
              unclassifiedRows,
              null,
              null,
              window,
              compare));
    }
    var liabilityAndEquity = new ArrayList<FinanceReportRow>();
    liabilityAndEquity.addAll(liabilityRows);
    liabilityAndEquity.addAll(equityRows);
    liabilityAndEquity.addAll(retainedRows);
    rows.add(
        totalRow(
            FinancialReportType.BALANCE_SHEET,
            "负债和所有者权益总计",
            "TOTAL",
            "合计",
            "LIABILITY_EQUITY",
            currency,
            liabilityAndEquity,
            null,
            null,
            window,
            compare));

    var totalsWithTotal = totalsOf(rows);
    var comparisonTotals =
        totalsOf(
            comparisonRows(rows, comparison, templates, FinancialReportType.BALANCE_SHEET, window, compare));
    var checks = new ArrayList<FinanceReportCheck>();
    checks.add(
        check(
            "借贷平衡（本期）",
            totalsWithTotal.periodDebit(),
            totalsWithTotal.periodCredit(),
            "本期借方合计必须等于本期贷方合计（每张已过账凭证都借贷相等 ⇒ 差额非 0 说明账有问题）。"));
    checks.add(
        check(
            "会计恒等式（期末）",
            asset,
            liability.add(equity).add(retained),
            "资产 = 负债 + 所有者权益 + 累计未结转损益。差额非 0 的常见原因："
                + "① 损益尚未结转到权益（系统**不自动结转**，这是如实行为）；"
                + "② 有科目没在 12.5.1.1 科目表登记（归入『未分类』段落，不并入合计）。"));
    return new FinanceReportGrouping(
        currency, rows, List.of(), totalsWithTotal, comparisonTotals, checks);
  }

  // ---------------------------------------------------------------- 利润表

  private FinanceReportGrouping incomeStatement(
      String currency,
      Map<String, Agg> current,
      Map<String, Agg> comparison,
      Map<String, TemplateView> templates,
      PeriodWindow window,
      PeriodWindow compare) {
    var buckets = bucketize(current, templates, FinancialReportType.INCOME_STATEMENT, "INCOME");
    var rows = new ArrayList<FinanceReportRow>();
    var revenueRows = new ArrayList<FinanceReportRow>();
    var expenseRows = new ArrayList<FinanceReportRow>();
    for (var bucket : buckets) {
      var row =
          toRow(
              bucket,
              currency,
              comparisonBucket(comparison, templates, FinancialReportType.INCOME_STATEMENT, bucket.lineCode),
              FinancialReportType.INCOME_STATEMENT,
              window,
              compare,
              false);
      rows.add(row);
      if ("REVENUE".equals(bucket.section)) {
        revenueRows.add(row);
      } else if ("EXPENSE".equals(bucket.section)) {
        expenseRows.add(row);
      }
    }
    var revenue = sumPeriodNet(revenueRows);
    var expense = sumPeriodNet(expenseRows);
    var profitRows = new ArrayList<FinanceReportRow>();
    profitRows.addAll(revenueRows);
    profitRows.addAll(expenseRows);
    rows.add(
        totalRow(
            FinancialReportType.INCOME_STATEMENT,
            "收入合计",
            "REVENUE",
            "收入",
            "REVENUE",
            currency,
            revenueRows,
            null,
            null,
            window,
            compare));
    rows.add(
        totalRow(
            FinancialReportType.INCOME_STATEMENT,
            "成本费用合计",
            "EXPENSE",
            "成本与费用",
            "EXPENSE",
            currency,
            expenseRows,
            null,
            null,
            window,
            compare));
    rows.add(
        totalRow(
            FinancialReportType.INCOME_STATEMENT,
            "本期损益",
            "TOTAL",
            "合计",
            "PROFIT",
            currency,
            profitRows,
            revenue.subtract(expense),
            revenue.subtract(expense),
            window,
            compare));
    var totals = totalsOf(rows);
    var comparisonTotals =
        totalsOf(
            comparisonRows(rows, comparison, templates, FinancialReportType.INCOME_STATEMENT, window, compare));
    var checks =
        List.of(
            check(
                "借贷平衡（本期）",
                totals.periodDebit(),
                totals.periodCredit(),
                "本期借方合计必须等于本期贷方合计。"));
    return new FinanceReportGrouping(currency, rows, List.of(), totals, comparisonTotals, checks);
  }

  // ---------------------------------------------------------------- 现金流量表

  private FinanceReportGrouping cashFlow(
      String currency, PeriodWindow window, String currencyFilter, Map<String, TemplateView> templates) {
    var cashSubjects = new ArrayList<String>();
    var groupBySubject = new LinkedHashMap<String, String>();
    for (var view : templates.values()) {
      if ("CASH".equals(view.cashRole())) {
        cashSubjects.add(view.subjectCode());
      } else if ("NON_CASH".equals(view.cashRole()) && !view.cashFlowGroup().isEmpty()) {
        groupBySubject.put(view.subjectCode(), view.cashFlowGroup());
      }
    }
    if (cashSubjects.isEmpty()) {
      return new FinanceReportGrouping(
          currency, List.of(), List.of(), totalsOf(List.of()), totalsOf(List.of()), List.of());
    }
    var items = cashFlowItems(window, currencyFilter, cashSubjects, groupBySubject);
    var byGroup = new LinkedHashMap<String, List<CashFlowItem>>();
    for (var item : items) {
      if (!item.currency().equals(currency)) continue;
      byGroup.computeIfAbsent(item.group(), k -> new ArrayList<>()).add(item);
    }
    var rows = new ArrayList<FinanceReportRow>();
    var grandIn = ZERO;
    var grandOut = ZERO;
    var vouchers = new LinkedHashSet<UUID>();
    var lineCount = 0;
    for (var group : FinancialReportCashFlowGroup.values()) {
      var list = byGroup.get(group.name());
      if (list == null || list.isEmpty()) continue;
      var inflow = ZERO;
      var outflow = ZERO;
      for (var item : list) {
        inflow = inflow.add(item.inflow());
        outflow = outflow.add(item.outflow());
        for (var line : item.lines()) {
          vouchers.add(line.voucherId());
          lineCount++;
        }
      }
      var bucket = new Bucket();
      bucket.section = group.name();
      bucket.sectionLabel = group.label();
      bucket.lineCode = "CASH_FLOW:" + group.name();
      bucket.lineName = group.label();
      bucket.subjectCount = 1;
      bucket.periodDebit = inflow;
      bucket.periodCredit = outflow;
      bucket.voucherCount = vouchers.size();
      bucket.lineCount = lineCount;
      rows.add(toRow(bucket, currency, null, FinancialReportType.CASH_FLOW, window, null, false));
      grandIn = grandIn.add(inflow);
      grandOut = grandOut.add(outflow);
    }
    var totalBucket = new Bucket();
    totalBucket.section = "TOTAL";
    totalBucket.sectionLabel = "合计";
    totalBucket.lineCode = "TOTAL:CASH_FLOW";
    totalBucket.lineName = "现金净增加额";
    totalBucket.subjectCount = 1;
    totalBucket.periodDebit = grandIn;
    totalBucket.periodCredit = grandOut;
    totalBucket.voucherCount = vouchers.size();
    totalBucket.lineCount = lineCount;
    rows.add(toRow(totalBucket, currency, null, FinancialReportType.CASH_FLOW, window, null, true));
    var totals = totalsOf(rows);
    return new FinanceReportGrouping(
        currency, rows, List.of(), totals, totalsOf(List.of()), List.of());
  }

  /**
   * 现金流逐笔归类。
   *
   * <p>口径：只看**含现金科目**的凭证；现金科目之间的转账（整张凭证都是现金科目）不进现金流量表。
   * 对含现金科目的凭证，按**对方（非现金）分录行**归类：对方在贷方 ⇒ 该组现金流入；对方在借方 ⇒ 流出。
   */
  private List<CashFlowItem> cashFlowItems(
      PeriodWindow window,
      String currencyFilter,
      List<String> cashSubjects,
      Map<String, String> groupBySubject) {
    var lines = scopeLines(window, currencyFilter, null, null, false);
    var byVoucher = new LinkedHashMap<UUID, List<LineRow>>();
    for (var line : lines) {
      byVoucher.computeIfAbsent(line.voucherId(), k -> new ArrayList<>()).add(line);
    }
    var items = new ArrayList<CashFlowItem>();
    for (var voucherLines : byVoucher.values()) {
      var cashLines = voucherLines.stream().filter(l -> cashSubjects.contains(l.subjectCode())).toList();
      if (cashLines.isEmpty()) continue;
      var counterpart = voucherLines.stream().filter(l -> !cashSubjects.contains(l.subjectCode())).toList();
      if (counterpart.isEmpty()) continue; // 现金科目之间的内部转账 ⇒ 不进现金流量表。
      var byGroup = new LinkedHashMap<String, BigDecimal[]>();
      var byGroupLines = new LinkedHashMap<String, List<LineRow>>();
      for (var line : counterpart) {
        var group =
            groupBySubject.getOrDefault(
                line.subjectCode(), FinancialReportCashFlowGroup.UNCLASSIFIED.name());
        var amounts = byGroup.computeIfAbsent(group, k -> new BigDecimal[] {ZERO, ZERO});
        if ("CREDIT".equals(line.lineType())) {
          amounts[0] = amounts[0].add(line.amount());
        } else {
          amounts[1] = amounts[1].add(line.amount());
        }
        byGroupLines.computeIfAbsent(group, k -> new ArrayList<>()).add(line);
      }
      for (var groupEntry : byGroup.entrySet()) {
        var amounts = groupEntry.getValue();
        var groupLines = new ArrayList<>(byGroupLines.get(groupEntry.getKey()));
        groupLines.addAll(cashLines);
        items.add(
            new CashFlowItem(
                voucherLines.get(0).currency(),
                groupEntry.getKey(),
                money(amounts[0]),
                money(amounts[1]),
                groupLines));
      }
    }
    return items;
  }

  // ---------------------------------------------------------------- 科目余额表

  private FinanceReportGrouping trialBalance(
      String currency,
      Map<String, Agg> current,
      Map<String, Agg> comparison,
      Map<String, TemplateView> templates,
      PeriodWindow window,
      PeriodWindow compare) {
    var buckets = bucketize(current, templates, FinancialReportType.TRIAL_BALANCE, null);
    var rows = new ArrayList<FinanceReportRow>();
    for (var bucket : buckets) {
      rows.add(
          toRow(
              bucket,
              currency,
              comparisonBucket(comparison, templates, FinancialReportType.TRIAL_BALANCE, bucket.lineCode),
              FinancialReportType.TRIAL_BALANCE,
              window,
              compare,
              false));
    }
    var totals = totalsOf(rows);
    // 试算平衡的合计行用**原始借贷净额**（期末借 − 期末贷）⇒ 与钻取端点的口径一致。
    rows.add(
        totalRow(
            FinancialReportType.TRIAL_BALANCE,
            "合计",
            "TOTAL",
            "合计",
            "ALL",
            currency,
            rows,
            totals.periodDebit().subtract(totals.periodCredit()),
            totals.closingDebit().subtract(totals.closingCredit()),
            window,
            compare));
    var totalsWithTotal = totalsOf(rows);
    var comparisonTotals =
        totalsOf(
            comparisonRows(rows, comparison, templates, FinancialReportType.TRIAL_BALANCE, window, compare));
    var checks =
        List.of(
            check(
                "借贷平衡（本期）",
                totalsWithTotal.periodDebit(),
                totalsWithTotal.periodCredit(),
                "本期借方合计必须等于本期贷方合计。"),
            check(
                "试算平衡（期末）",
                totalsWithTotal.closingDebit(),
                totalsWithTotal.closingCredit(),
                "期末借方合计必须等于期末贷方合计。"));
    return new FinanceReportGrouping(
        currency, rows, List.of(), totalsWithTotal, comparisonTotals, checks);
  }

  // ---------------------------------------------------------------- 明细账

  private FinanceReportGrouping generalLedger(
      String currency,
      Map<String, Agg> current,
      Map<String, Agg> comparison,
      Map<String, TemplateView> templates,
      PeriodWindow window,
      PeriodWindow compare,
      String subjectCode,
      StringBuilder notice) {
    var code = subjectCode == null ? "" : subjectCode.trim();
    var rows = new ArrayList<FinanceReportRow>();
    List<FinanceReportLedgerEntry> entries = new ArrayList<>();
    if (code.isEmpty()) {
      notice.append(" ⚠️ 明细账必须指定一个科目编码（逐笔展开的是**一个**科目）。");
      return new FinanceReportGrouping(
          currency, rows, entries, totalsOf(List.of()), totalsOf(List.of()), List.of());
    }
    Agg agg = null;
    for (var candidate : current.values()) {
      if (candidate.subjectCode.equals(code)) {
        agg = candidate;
        break;
      }
    }
    if (agg != null) {
      var target = targetLine(FinancialReportType.GENERAL_LEDGER, agg, templates);
      var bucket = new Bucket();
      bucket.section = target.section();
      bucket.sectionLabel = target.sectionLabel();
      bucket.lineCode = target.lineCode();
      bucket.lineName = target.lineName();
      merge(bucket, agg);
      rows.add(
          toRow(
              bucket,
              agg.currency,
              comparisonBucket(comparison, templates, FinancialReportType.GENERAL_LEDGER, target.lineCode()),
              FinancialReportType.GENERAL_LEDGER,
              window,
              compare,
              false));
      entries = ledgerEntries(agg, window);
    }
    var totals = totalsOf(rows);
    var comparisonTotals =
        totalsOf(
            comparisonRows(rows, comparison, templates, FinancialReportType.GENERAL_LEDGER, window, compare));
    var checks =
        List.of(
            check(
                "借贷平衡（本期）",
                totals.periodDebit(),
                totals.periodCredit(),
                "本期借方合计必须等于本期贷方合计。"));
    return new FinanceReportGrouping(currency, rows, entries, totals, comparisonTotals, checks);
  }

  /** 明细账逐笔（带逐笔余额：从期初余额起算）。 */
  private List<FinanceReportLedgerEntry> ledgerEntries(Agg agg, PeriodWindow window) {
    var lines = scopeLines(window, agg.currency, agg.subjectCode, null, false);
    var entries = new ArrayList<FinanceReportLedgerEntry>();
    var running = signed(agg.openingDebit, agg.openingCredit, agg.balanceDirection);
    // 对方科目必须来自**同一张凭证的其它分录行** ⇒ 这里按凭证 id 再取一次全部分录行。
    var voucherIds = lines.stream().map(LineRow::voucherId).distinct().toList();
    var byVoucher = new LinkedHashMap<UUID, List<LineRow>>();
    for (var line : voucherLines(window, agg.currency, voucherIds)) {
      byVoucher.computeIfAbsent(line.voucherId(), k -> new ArrayList<>()).add(line);
    }
    for (var line : lines) {
      var counterpart =
          byVoucher.getOrDefault(line.voucherId(), List.of()).stream()
              .filter(other -> !other.lineId.equals(line.lineId))
              .map(other -> other.subjectCode + " " + other.subjectName)
              .reduce((a, b) -> a + " / " + b)
              .orElse("");
      var debit = "DEBIT".equals(line.lineType) ? line.amount : ZERO;
      var credit = "CREDIT".equals(line.lineType) ? line.amount : ZERO;
      running = money(running.add(signed(debit, credit, agg.balanceDirection)));
      entries.add(
          new FinanceReportLedgerEntry(
              "GL:" + line.currency + ":" + agg.subjectCode + ":" + line.lineId,
              agg.subjectCode,
              line.subjectName,
              line.currency,
              line.voucherDate,
              line.periodCode,
              line.voucherId,
              line.voucherNo,
              line.voucherStatus,
              line.lineId,
              line.lineNo,
              line.lineType,
              "DEBIT".equals(line.lineType) ? "借" : "贷",
              line.summary,
              line.amount,
              debit,
              credit,
              running,
              counterpart,
              "/finance/vouchers/" + line.voucherId));
      if (entries.size() >= MAX_LEDGER_ENTRIES) {
        break;
      }
    }
    return entries;
  }

  // ================================================================ 钻取

  /**
   * 钻取到凭证：给出构成某个报表行的**真实凭证 + 分录行**。
   *
   * <p>🔴 钻取走的是**同一套取数口径**（重算一遍）⇒ 钻出来的合计必然等于报表上的数字。
   */
  public FinanceReportDrill drill(
      FinancialReportType type,
      String lineCode,
      String subjectCode,
      String currency,
      UUID periodFromId,
      UUID periodToId) {
    if (lineCode == null || lineCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定要钻取的报表行（lineCode）");
    }
    var window = requireWindow(periodFromId, periodToId);
    var currencyFilter = currency == null || currency.isBlank() ? null : currency.trim().toUpperCase(Locale.ROOT);
    var templates = templateViews(type);
    var current = aggregates(window, currencyFilter);
    var targetCurrency = currencyFilter;
    if (targetCurrency == null) {
      targetCurrency = currenciesOf(current).stream().findFirst().orElse(null);
    }
    if (targetCurrency == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该期间范围内没有任何已过账的凭证");
    }
    var aggs = byCurrency(current, targetCurrency);
    var subjects = new ArrayList<String>();
    String section = "";
    String sectionLabel = "";
    String direction = "";
    String lineName = lineCode;
    var cumulative = type == FinancialReportType.BALANCE_SHEET || type == FinancialReportType.TRIAL_BALANCE;
    if (lineCode.startsWith("TOTAL:")) {
      var wanted = lineCode.substring("TOTAL:".length());
      section = wanted;
      switch (wanted) {
        case "ALL" -> subjects.addAll(aggs.values().stream().map(a -> a.subjectCode).toList());
        case "LIABILITY_EQUITY" -> {
          for (var agg : aggs.values()) {
            var target = targetLine(type, agg, templates);
            if (target.section.equals("LIABILITY")
                || target.section.equals("EQUITY")
                || target.section.equals("RETAINED")) {
              subjects.add(agg.subjectCode);
            }
          }
        }
        case "PROFIT" -> {
          for (var agg : aggs.values()) {
            var target = targetLine(type, agg, templates);
            if (target.section.equals("REVENUE") || target.section.equals("EXPENSE")) {
              subjects.add(agg.subjectCode);
            }
          }
        }
        default -> {
          for (var agg : aggs.values()) {
            var target = targetLine(type, agg, templates);
            if (target.section.equals(wanted)) {
              subjects.add(agg.subjectCode);
              sectionLabel = target.sectionLabel;
            }
          }
        }
      }
    } else if (lineCode.startsWith("CASH_FLOW:")) {
      section = lineCode.substring("CASH_FLOW:".length());
      sectionLabel = FinancialReportCashFlowGroup.valueOf(section).label();
    } else {
      for (var agg : aggs.values()) {
        var target = targetLine(type, agg, templates);
        if (!target.lineCode.equals(lineCode)) continue;
        subjects.add(agg.subjectCode);
        lineName = target.lineName;
        section = target.section;
        sectionLabel = target.sectionLabel;
        if (subjects.size() == 1) direction = agg.balanceDirection;
      }
      if (subjectCode != null && !subjectCode.isBlank() && !subjects.contains(subjectCode.trim())) {
        subjects.add(subjectCode.trim());
      }
    }
    if (subjects.isEmpty() && !lineCode.startsWith("CASH_FLOW:")) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "该期间范围内没有构成这一行的已过账凭证（行 "
              + lineCode
              + "，该期间该币种下已过账的科目 "
              + aggs.size()
              + " 个）：这一行本期没有发生额");
    }
    List<LineRow> lines;
    var cashIn = ZERO;
    var cashOut = ZERO;
    if (type == FinancialReportType.CASH_FLOW) {
      var cashSubjects = new ArrayList<String>();
      var groupBySubject = new LinkedHashMap<String, String>();
      for (var view : templates.values()) {
        if ("CASH".equals(view.cashRole())) {
          cashSubjects.add(view.subjectCode);
        } else if ("NON_CASH".equals(view.cashRole()) && !view.cashFlowGroup().isEmpty()) {
          groupBySubject.put(view.subjectCode, view.cashFlowGroup());
        }
      }
      var items = cashFlowItems(window, currencyFilter, cashSubjects, groupBySubject);
      var wanted =
          lineCode.startsWith("CASH_FLOW:")
              ? lineCode.substring("CASH_FLOW:".length())
              : lineCode.startsWith("TOTAL:") ? "" : lineCode;
      lines = new ArrayList<>();
      for (var item : items) {
        if (!item.currency.equals(targetCurrency)) continue;
        if (!wanted.isEmpty() && !item.group.equals(wanted)) continue;
        lines.addAll(item.lines);
        cashIn = cashIn.add(item.inflow);
        cashOut = cashOut.add(item.outflow);
      }
    } else {
      lines = scopeLines(window, currencyFilter, null, subjects, cumulative);
    }
    if (lines.size() > MAX_DRILL_ENTRIES) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "这一行涉及的凭证太多（" + lines.size() + " 行），请缩小期间范围后再钻取");
    }
    var debit = ZERO;
    var credit = ZERO;
    var entries = new ArrayList<FinanceReportDrillEntry>();
    var vouchers = new LinkedHashSet<UUID>();
    for (var line : lines) {
      if ("DEBIT".equals(line.lineType)) {
        debit = debit.add(line.amount);
      } else {
        credit = credit.add(line.amount);
      }
      vouchers.add(line.voucherId);
      entries.add(
          new FinanceReportDrillEntry(
              line.voucherId,
              line.voucherNo,
              line.voucherDate,
              line.periodCode,
              line.voucherStatus,
              line.lineId,
              line.lineNo,
              line.lineType,
              "DEBIT".equals(line.lineType) ? "借" : "贷",
              line.subjectCode,
              line.subjectName,
              line.summary,
              line.amount,
              line.currency,
              "/finance/vouchers/" + line.voucherId));
    }
    // 🔴 现金流量表按**对方科目**归类 ⇒ 借方合计 = 现金流入、贷方合计 = 现金流出。
    if (type == FinancialReportType.CASH_FLOW) {
      debit = cashIn;
      credit = cashOut;
    }
    // 净额用**与报表行同一套符号规则**算 ⇒ 钻出来的数必然等于报表上的数。
    var netTotal = signedOf(type, drillSignSection(type, section), direction, debit, credit);
    return new FinanceReportDrill(
        type.name(),
        type.label(),
        lineCode,
        lineName,
        section,
        sectionLabel,
        subjectCode == null ? "" : subjectCode.trim(),
        subjectCode == null ? "" : subjectCode.trim(),
        targetCurrency,
        window.label(),
        money(debit),
        money(credit),
        netTotal,
        vouchers.size(),
        entries.size(),
        entries);
  }

  /** 合计行的符号段落（把「负债和所有者权益」「本期损益」映射到同号的段落）。 */
  private static String drillSignSection(FinancialReportType type, String section) {
    if (type != FinancialReportType.BALANCE_SHEET && type != FinancialReportType.INCOME_STATEMENT) {
      return section;
    }
    return switch (section) {
      case "LIABILITY_EQUITY" -> "LIABILITY";
      case "PROFIT" -> "REVENUE";
      default -> section;
    };
  }

  // ================================================================ 导出

  /** 导出一张报表（CSV / JSON 真内容 + 一条 append-only 留痕）。 */
  @Transactional
  public FinanceReportExportFile export(
      FinancialReportType type,
      String format,
      UUID periodFromId,
      UUID periodToId,
      UUID comparePeriodFromId,
      UUID comparePeriodToId,
      String currency,
      String subjectCode,
      AccessService.Access actor) {
    var fmt = (format == null || format.isBlank() ? "CSV" : format).trim().toUpperCase(Locale.ROOT);
    if (!fmt.equals("CSV") && !fmt.equals("JSON")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导出格式只支持 CSV 或 JSON");
    }
    var result =
        report(
            type,
            periodFromId,
            periodToId,
            comparePeriodFromId,
            comparePeriodToId,
            currency,
            subjectCode);
    var rowCount = 0;
    for (var group : result.currencyGroups()) {
      rowCount += group.rows().size();
    }
    if (rowCount > MAX_EXPORT_ROWS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "一次最多导出 " + MAX_EXPORT_ROWS + " 行，请缩小期间范围");
    }
    var content = fmt.equals("CSV") ? renderCsv(result) : renderJson(result);
    var bytes = content.getBytes(StandardCharsets.UTF_8);
    var hash = sha256(bytes);
    var filename =
        "financial-report-"
            + type.name().toLowerCase(Locale.ROOT)
            + "-"
            + result.periodFromCode()
            + "-"
            + result.periodToCode()
            + "."
            + fmt.toLowerCase(Locale.ROOT);
    sql.execute(
        "insert into finance_report_export_event(id,tenant_id,report_type,format,period_from_id,"
            + "period_to_id,compare_period_from_id,compare_period_to_id,currency_scope,subject_code,"
            + "row_count,currency_count,voucher_count,line_count,filename,content_bytes,content_hash,"
            + "actor_id,occurred_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        actor.tenantId(),
        type.name(),
        fmt,
        periodFromId,
        periodToId,
        comparePeriodFromId,
        comparePeriodToId,
        result.currencyScope(),
        subjectCode == null ? "" : subjectCode.trim(),
        rowCount,
        result.currencyGroups().size(),
        result.voucherCount(),
        result.lineCount(),
        filename,
        bytes.length,
        hash,
        actor.userId(),
        OffsetDateTime.now(ZoneOffset.UTC));
    return new FinanceReportExportFile(
        filename,
        fmt.equals("CSV") ? "text/csv; charset=utf-8" : "application/json; charset=utf-8",
        content,
        rowCount,
        result.currencyGroups().size(),
        hash);
  }

  /** CSV 渲染（**带 UTF-8 BOM**：Excel 打开中文不乱码）。 */
  private String renderCsv(FinanceReportResult result) {
    var sb = new StringBuilder();
    sb.append('\uFEFF');
    sb.append(
        "报表,报表行编码,报表行名称,段落,币种,科目编码,科目名称,期初借,期初贷,期初余额,"
            + "本期借,本期贷,本期净额,期末借,期末贷,期末余额,对比期本期净额,对比期期末余额,"
            + "本期净额变动,期末余额变动,凭证数,分录行数,钻取路径\n");
    for (var group : result.currencyGroups()) {
      for (var row : group.rows()) {
        sb.append(csvCell(result.reportTypeLabel())).append(',')
            .append(csvCell(row.lineCode())).append(',')
            .append(csvCell(row.lineName())).append(',')
            .append(csvCell(row.sectionLabel())).append(',')
            .append(csvCell(row.currency())).append(',')
            .append(csvCell(nullToEmpty(row.subjectCode()))).append(',')
            .append(csvCell(nullToEmpty(row.subjectName()))).append(',')
            .append(csvCell(plain(row.openingDebit()))).append(',')
            .append(csvCell(plain(row.openingCredit()))).append(',')
            .append(csvCell(plain(row.openingBalance()))).append(',')
            .append(csvCell(plain(row.periodDebit()))).append(',')
            .append(csvCell(plain(row.periodCredit()))).append(',')
            .append(csvCell(plain(row.periodNet()))).append(',')
            .append(csvCell(plain(row.closingDebit()))).append(',')
            .append(csvCell(plain(row.closingCredit()))).append(',')
            .append(csvCell(plain(row.closingBalance()))).append(',')
            .append(csvCell(plain(row.comparisonPeriodNet()))).append(',')
            .append(csvCell(plain(row.comparisonClosingBalance()))).append(',')
            .append(csvCell(plain(row.deltaPeriodNet()))).append(',')
            .append(csvCell(plain(row.deltaClosingBalance()))).append(',')
            .append(csvCell(String.valueOf(row.voucherCount()))).append(',')
            .append(csvCell(String.valueOf(row.lineCount()))).append(',')
            .append(csvCell(row.drillPath()))
            .append('\n');
      }
      for (var entry : group.ledgerEntries()) {
        sb.append(csvCell(result.reportTypeLabel())).append(',')
            .append(csvCell(entry.subjectCode())).append(',')
            .append(csvCell(entry.subjectName())).append(',')
            .append(csvCell("明细账")).append(',')
            .append(csvCell(entry.currency())).append(',')
            .append(csvCell(entry.subjectCode())).append(',')
            .append(csvCell(entry.subjectName())).append(',')
            .append(csvCell("")).append(',')
            .append(csvCell("")).append(',')
            .append(csvCell("")).append(',')
            .append(csvCell(plain(entry.debitAmount()))).append(',')
            .append(csvCell(plain(entry.creditAmount()))).append(',')
            .append(csvCell(plain(entry.runningBalance()))).append(',')
            .append(csvCell("")).append(',')
            .append(csvCell("")).append(',')
            .append(csvCell("")).append(',')
            .append(csvCell("")).append(',')
            .append(csvCell("")).append(',')
            .append(csvCell("")).append(',')
            .append(csvCell("")).append(',')
            .append(csvCell("")).append(',')
            .append(csvCell(entry.voucherNo() + " / " + entry.periodCode() + " / 行" + entry.lineNo()))
            .append(',')
            .append(csvCell(entry.drillPath()))
            .append('\n');
      }
    }
    return sb.toString();
  }

  /** JSON 渲染（真内容：用 Jackson 序列化，字段名与契约一致）。 */
  private String renderJson(FinanceReportResult result) {
    try {
      return json.writerWithDefaultPrettyPrinter().writeValueAsString(result);
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "报表导出失败：" + e.getMessage());
    }
  }

  // ================================================================ 取数

  /** 期间范围解析。 */
  private PeriodWindow requireWindow(UUID fromId, UUID toId) {
    if (fromId == null || toId == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "期间范围不能为空：必须给出起始期间与结束期间");
    }
    var from = sql.fetchOne("select * from finance_voucher_period where id = ?", fromId);
    if (from == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "起始会计期间不存在");
    }
    var to = sql.fetchOne("select * from finance_voucher_period where id = ?", toId);
    if (to == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "结束会计期间不存在");
    }
    var start = from.get("period_start", LocalDate.class);
    var end = to.get("period_end", LocalDate.class);
    if (end.isBefore(start)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "期间范围不合法：结束期间 "
              + to.get("period_code", String.class)
              + " 早于起始期间 "
              + from.get("period_code", String.class));
    }
    return new PeriodWindow(
        fromId,
        toId,
        from.get("period_code", String.class),
        to.get("period_code", String.class),
        start,
        end);
  }

  /**
   * 区间聚合（期初 + 本期）。
   *
   * <p>🔴 取数条件只有两个：{@code v.status IN ('POSTED','REVERSED')}（草稿 / 作废不进）
   * 与期间落在范围内（本期 = 期间完全落在范围内；期初 = 期间开始早于范围起点的全部）。
   */
  private Map<String, Agg> aggregates(PeriodWindow window, String currencyFilter) {
    var result = new LinkedHashMap<String, Agg>();
    for (var row :
        sql.fetch(
            "select v.currency, l.subject_code,"
                + " (array_agg(l.subject_name order by v.created_at desc, l.line_no))[1] as subject_name,"
                + " max(s.category) as category, max(s.balance_direction) as balance_direction,"
                + " sum(l.amount) filter (where l.line_type='DEBIT') as debit,"
                + " sum(l.amount) filter (where l.line_type='CREDIT') as credit,"
                + " count(distinct v.id) as voucher_count, count(*) as line_count"
                + " from finance_voucher v"
                + " join finance_voucher_line l on l.tenant_id = v.tenant_id and l.voucher_id = v.id"
                + " join finance_voucher_period p on p.tenant_id = v.tenant_id and p.id = v.period_id"
                + " left join finance_account_subject s"
                + "   on s.tenant_id = l.tenant_id and s.subject_code = l.subject_code"
                + " where v.status in ('POSTED','REVERSED') and p.period_start < ?"
                + (currencyFilter == null ? "" : " and v.currency = ?")
                + " group by v.currency, l.subject_code order by v.currency, l.subject_code",
            currencyFilter == null
                ? new Object[] {window.start()}
                : new Object[] {window.start(), currencyFilter})) {
      var agg = aggOf(result, row);
      agg.addOpening(nz(row.get("debit", BigDecimal.class)), nz(row.get("credit", BigDecimal.class)));
    }
    for (var row :
        sql.fetch(
            "select v.currency, l.subject_code,"
                + " (array_agg(l.subject_name order by v.created_at desc, l.line_no))[1] as subject_name,"
                + " max(s.category) as category, max(s.balance_direction) as balance_direction,"
                + " sum(l.amount) filter (where l.line_type='DEBIT') as debit,"
                + " sum(l.amount) filter (where l.line_type='CREDIT') as credit,"
                + " count(distinct v.id) as voucher_count, count(*) as line_count"
                + " from finance_voucher v"
                + " join finance_voucher_line l on l.tenant_id = v.tenant_id and l.voucher_id = v.id"
                + " join finance_voucher_period p on p.tenant_id = v.tenant_id and p.id = v.period_id"
                + " left join finance_account_subject s"
                + "   on s.tenant_id = l.tenant_id and s.subject_code = l.subject_code"
                + " where v.status in ('POSTED','REVERSED')"
                + "   and p.period_start >= ? and p.period_end <= ?"
                + (currencyFilter == null ? "" : " and v.currency = ?")
                + " group by v.currency, l.subject_code order by v.currency, l.subject_code",
            currencyFilter == null
                ? new Object[] {window.start(), window.end()}
                : new Object[] {window.start(), window.end(), currencyFilter})) {
      var agg = aggOf(result, row);
      agg.addPeriod(
          nz(row.get("debit", BigDecimal.class)),
          nz(row.get("credit", BigDecimal.class)),
          row.get("voucher_count", Long.class),
          row.get("line_count", Long.class));
    }
    return result;
  }

  private Agg aggOf(Map<String, Agg> result, org.jooq.Record row) {
    var currency = row.get("currency", String.class);
    var subjectCode = row.get("subject_code", String.class);
    var mapKey = currency + "|" + subjectCode;
    var agg = result.get(mapKey);
    if (agg == null) {
      agg = new Agg(currency, subjectCode);
      result.put(mapKey, agg);
    }
    agg.subjectName = nullToEmpty(row.get("subject_name", String.class));
    agg.category = nullToEmpty(row.get("category", String.class));
    agg.balanceDirection = nullToEmpty(row.get("balance_direction", String.class));
    return agg;
  }

  /** 本期被排除的草稿凭证数（草稿不是账，明确说出来）。 */
  private long draftCount(PeriodWindow window, String currencyFilter) {
    var row =
        sql.fetchOne(
            "select count(*) from finance_voucher v"
                + " join finance_voucher_period p on p.tenant_id = v.tenant_id and p.id = v.period_id"
                + " where v.status = 'DRAFT'"
                + "   and p.period_start >= ? and p.period_end <= ?"
                + (currencyFilter == null ? "" : " and v.currency = ?"),
            currencyFilter == null
                ? new Object[] {window.start(), window.end()}
                : new Object[] {window.start(), window.end(), currencyFilter});
    return row == null ? 0L : row.get(0, Long.class);
  }

  /** 本期已红冲的原凭证数。 */
  private long reversedCount(PeriodWindow window, String currencyFilter) {
    var row =
        sql.fetchOne(
            "select count(*) from finance_voucher v"
                + " join finance_voucher_period p on p.tenant_id = v.tenant_id and p.id = v.period_id"
                + " where v.status = 'REVERSED'"
                + "   and p.period_start >= ? and p.period_end <= ?"
                + (currencyFilter == null ? "" : " and v.currency = ?"),
            currencyFilter == null
                ? new Object[] {window.start(), window.end()}
                : new Object[] {window.start(), window.end(), currencyFilter});
    return row == null ? 0L : row.get(0, Long.class);
  }

  /** 某几张凭证上的**全部分录行**（明细账的「对方科目」从这里来）。 */
  private List<LineRow> voucherLines(PeriodWindow window, String currencyFilter, List<UUID> voucherIds) {
    if (voucherIds.isEmpty()) return List.of();
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("v.status in ('POSTED','REVERSED')");
    where.add(
        "v.id in (" + String.join(",", java.util.Collections.nCopies(voucherIds.size(), "?")) + ")");
    binds.addAll(voucherIds);
    if (currencyFilter != null) {
      where.add("v.currency = ?");
      binds.add(currencyFilter);
    }
    where.add("p.period_start >= ? and p.period_end <= ?");
    binds.add(window.start());
    binds.add(window.end());
    var rows =
        sql.fetch(
            "select v.id as voucher_id, v.voucher_no, v.voucher_date, v.status as voucher_status,"
                + " p.period_code, l.id as line_id, l.line_no, l.line_type, l.subject_code,"
                + " l.subject_name, l.summary, l.amount, l.currency"
                + " from finance_voucher v"
                + " join finance_voucher_line l on l.tenant_id = v.tenant_id and l.voucher_id = v.id"
                + " join finance_voucher_period p on p.tenant_id = v.tenant_id and p.id = v.period_id"
                + " where "
                + String.join(" and ", where)
                + " order by v.voucher_date, v.voucher_no, l.line_no",
            binds.toArray());
    var lines = new ArrayList<LineRow>();
    for (var r : rows) {
      lines.add(
          new LineRow(
              r.get("voucher_id", UUID.class),
              r.get("voucher_no", String.class),
              r.get("voucher_date", LocalDate.class),
              r.get("period_code", String.class),
              r.get("voucher_status", String.class),
              r.get("line_id", UUID.class),
              r.get("line_no", Integer.class),
              r.get("line_type", String.class),
              r.get("subject_code", String.class),
              nullToEmpty(r.get("subject_name", String.class)),
              nullToEmpty(r.get("summary", String.class)),
              nz(r.get("amount", BigDecimal.class)),
              r.get("currency", String.class)));
    }
    return lines;
  }

  /**
   * 区间内的分录行（**只读已过账 / 已红冲**）。
   *
   * @param cumulative true ⇒ 含期初（余额类报表用）；false ⇒ 只含本期（发生额类报表用）
   */
  private List<LineRow> scopeLines(
      PeriodWindow window,
      String currencyFilter,
      String subjectCode,
      List<String> subjects,
      boolean cumulative) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("v.status in ('POSTED','REVERSED')");
    if (subjectCode != null && !subjectCode.isBlank()) {
      where.add("l.subject_code = ?");
      binds.add(subjectCode.trim());
    } else if (subjects != null && !subjects.isEmpty()) {
      where.add(
          "l.subject_code in ("
              + String.join(",", java.util.Collections.nCopies(subjects.size(), "?"))
              + ")");
      binds.addAll(subjects);
    }
    if (currencyFilter != null) {
      where.add("v.currency = ?");
      binds.add(currencyFilter);
    }
    if (cumulative) {
      where.add("(p.period_start < ? or (p.period_start >= ? and p.period_end <= ?))");
      binds.add(window.start());
      binds.add(window.start());
      binds.add(window.end());
    } else {
      where.add("p.period_start >= ? and p.period_end <= ?");
      binds.add(window.start());
      binds.add(window.end());
    }
    var clause = " where " + String.join(" and ", where);
    var rows =
        sql.fetch(
            "select v.id as voucher_id, v.voucher_no, v.voucher_date, v.status as voucher_status,"
                + " p.period_code, l.id as line_id, l.line_no, l.line_type, l.subject_code,"
                + " l.subject_name, l.summary, l.amount, l.currency"
                + " from finance_voucher v"
                + " join finance_voucher_line l on l.tenant_id = v.tenant_id and l.voucher_id = v.id"
                + " join finance_voucher_period p on p.tenant_id = v.tenant_id and p.id = v.period_id"
                + clause
                + " order by v.voucher_date, v.voucher_no, l.line_no",
            binds.toArray());
    var lines = new ArrayList<LineRow>();
    for (var r : rows) {
      lines.add(
          new LineRow(
              r.get("voucher_id", UUID.class),
              r.get("voucher_no", String.class),
              r.get("voucher_date", LocalDate.class),
              r.get("period_code", String.class),
              r.get("voucher_status", String.class),
              r.get("line_id", UUID.class),
              r.get("line_no", Integer.class),
              r.get("line_type", String.class),
              r.get("subject_code", String.class),
              nullToEmpty(r.get("subject_name", String.class)),
              nullToEmpty(r.get("summary", String.class)),
              nz(r.get("amount", BigDecimal.class)),
              r.get("currency", String.class)));
    }
    return lines;
  }

  // ================================================================ 行组装

  /** 一行报表的目标行（模板优先，没有模板就按科目自身成行）。 */
  private record TargetLine(
      String lineCode, String lineName, String section, String sectionLabel, int sortOrder) {}

  /**
   * 把科目聚成报表行。
   *
   * @param filter "INCOME" ⇒ 只留损益类科目（利润表用）
   */
  private List<Bucket> bucketize(
      Map<String, Agg> current,
      Map<String, TemplateView> templates,
      FinancialReportType type,
      String filter) {
    var buckets = new LinkedHashMap<String, Bucket>();
    for (var agg : current.values()) {
      if ("INCOME".equals(filter) && !isProfitOrCost(agg, templates, type)) continue;
      var target = targetLine(type, agg, templates);
      var bucket = buckets.get(target.lineCode());
      if (bucket == null) {
        bucket = new Bucket();
        bucket.section = target.section();
        bucket.sectionLabel = target.sectionLabel();
        bucket.lineCode = target.lineCode();
        bucket.lineName = target.lineName();
        buckets.put(target.lineCode(), bucket);
      }
      merge(bucket, agg);
    }
    return new ArrayList<>(buckets.values());
  }

  private TargetLine targetLine(
      FinancialReportType type, Agg agg, Map<String, TemplateView> templates) {
    var view = templates.get(type.name() + "|" + agg.subjectCode);
    if (view != null) {
      var section = view.section().isEmpty() ? defaultSection(type, agg) : view.section();
      var label = view.section().isEmpty() ? defaultSectionLabel(type, agg) : view.section();
      return new TargetLine(
          view.lineCode(),
          view.lineName().isEmpty() ? agg.subjectName : view.lineName(),
          section,
          label,
          view.sortOrder());
    }
    var section = defaultSection(type, agg);
    return new TargetLine(
        agg.subjectCode,
        agg.subjectName.isEmpty() ? agg.subjectCode : agg.subjectName,
        section,
        defaultSectionLabel(type, agg),
        Integer.MAX_VALUE);
  }

  /** 默认段落（没有模板时按科目类别归段）。 */
  private String defaultSection(FinancialReportType type, Agg agg) {
    var category = agg.category;
    return switch (type) {
      case BALANCE_SHEET -> {
        if (category.equals("ASSET")) yield "ASSET";
        if (category.equals("LIABILITY")) yield "LIABILITY";
        if (category.equals("EQUITY")) yield "EQUITY";
        if (category.equals("PROFIT_LOSS") || category.equals("COST")) yield "RETAINED";
        yield "UNCLASSIFIED";
      }
      case INCOME_STATEMENT -> {
        if (category.equals("COST")) yield "EXPENSE";
        if (category.equals("PROFIT_LOSS")) {
          yield agg.balanceDirection.equals("CREDIT") ? "REVENUE" : "EXPENSE";
        }
        yield "UNCLASSIFIED";
      }
      case CASH_FLOW -> "CASH_UNCLASSIFIED";
      default -> "SUBJECT";
    };
  }

  private String defaultSectionLabel(FinancialReportType type, Agg agg) {
    return switch (defaultSection(type, agg)) {
      case "ASSET" -> FinancialReportSection.ASSET.label();
      case "LIABILITY" -> FinancialReportSection.LIABILITY.label();
      case "EQUITY" -> FinancialReportSection.EQUITY.label();
      case "RETAINED" -> FinancialReportSection.RETAINED.label();
      case "UNCLASSIFIED" -> FinancialReportSection.UNCLASSIFIED.label();
      case "REVENUE" -> FinancialReportSection.REVENUE.label();
      case "EXPENSE" -> FinancialReportSection.EXPENSE.label();
      case "CASH_UNCLASSIFIED" -> FinancialReportSection.CASH_UNCLASSIFIED.label();
      case "SUBJECT" -> FinancialReportSection.SUBJECT.label();
      default -> FinancialReportSection.TOTAL.label();
    };
  }

  private void merge(Bucket bucket, Agg agg) {
    bucket.openingDebit = bucket.openingDebit.add(agg.openingDebit);
    bucket.openingCredit = bucket.openingCredit.add(agg.openingCredit);
    bucket.periodDebit = bucket.periodDebit.add(agg.periodDebit);
    bucket.periodCredit = bucket.periodCredit.add(agg.periodCredit);
    bucket.voucherCount += agg.voucherCount;
    bucket.lineCount += agg.lineCount;
    bucket.subjectCount++;
    if (bucket.subjectCount == 1) {
      bucket.subjectCode = agg.subjectCode;
      bucket.subjectName = agg.subjectName;
      bucket.category = agg.category;
      bucket.balanceDirection = agg.balanceDirection;
    } else {
      // 归并了多个科目 ⇒ 不再指向单一科目（金额按借贷净额呈现，drill 走行编码）。
      bucket.subjectCode = null;
      bucket.subjectName = null;
      bucket.category = "";
      bucket.balanceDirection = "";
    }
  }

  private FinanceReportRow toRow(
      Bucket bucket,
      String currency,
      Bucket comparisonBucket,
      FinancialReportType type,
      PeriodWindow window,
      PeriodWindow compare,
      boolean totalRow) {
    // 利润表与现金流量表是**本期发生额**口径 ⇒ 不含期初；资产负债表 / 科目余额表 / 明细账是**余额**口径 ⇒ 含期初。
    var periodOnly =
        type == FinancialReportType.INCOME_STATEMENT || type == FinancialReportType.CASH_FLOW;
    var openingDebitGross = periodOnly ? ZERO : money(bucket.openingDebit);
    var openingCreditGross = periodOnly ? ZERO : money(bucket.openingCredit);
    var closingDebitGross =
        periodOnly ? money(bucket.periodDebit) : money(bucket.openingDebit.add(bucket.periodDebit));
    var closingCreditGross =
        periodOnly ? money(bucket.periodCredit) : money(bucket.openingCredit.add(bucket.periodCredit));
    // 🔴 **余额列按净额落在借方或贷方一侧**：红冲过的科目本期借贷各 700 ⇒ 期末余额是 0，
    //   不是「借 700 / 贷 700」。本期发生额列仍然是**毛额**（借贷各 700 就是各 700）。
    var openingNet = money(openingDebitGross.subtract(openingCreditGross));
    var closingNet = money(closingDebitGross.subtract(closingCreditGross));
    var openingDebit = openingNet.max(ZERO);
    var openingCredit = openingNet.negate().max(ZERO);
    var closingDebit = closingNet.max(ZERO);
    var closingCredit = closingNet.negate().max(ZERO);
    var openingBalance =
        periodOnly
            ? ZERO
            : signedOf(type, bucket.section, bucket.balanceDirection, openingDebit, openingCredit);
    var closingBalance =
        signedOf(type, bucket.section, bucket.balanceDirection, closingDebit, closingCredit);
    var periodNet =
        signedOf(
            type, bucket.section, bucket.balanceDirection, money(bucket.periodDebit), money(bucket.periodCredit));
    var comparisonPeriodNet = ZERO;
    var comparisonClosingBalance = ZERO;
    if (comparisonBucket != null) {
      var comparisonClosingDebitGross =
          periodOnly
              ? money(comparisonBucket.periodDebit)
              : money(comparisonBucket.openingDebit.add(comparisonBucket.periodDebit));
      var comparisonClosingCreditGross =
          periodOnly
              ? money(comparisonBucket.periodCredit)
              : money(comparisonBucket.openingCredit.add(comparisonBucket.periodCredit));
      var comparisonNet =
          money(comparisonClosingDebitGross.subtract(comparisonClosingCreditGross));
      var comparisonDebit = comparisonNet.max(ZERO);
      var comparisonCredit = comparisonNet.negate().max(ZERO);
      comparisonClosingBalance =
          signedOf(
              type,
              comparisonBucket.section,
              comparisonBucket.balanceDirection,
              comparisonDebit,
              comparisonCredit);
      comparisonPeriodNet =
          signedOf(
              type,
              comparisonBucket.section,
              comparisonBucket.balanceDirection,
              money(comparisonBucket.periodDebit),
              money(comparisonBucket.periodCredit));
    }
    var drillPath = drillPath(type, bucket, currency, window, compare);
    return new FinanceReportRow(
        rowKey(currency, bucket.lineCode),
        bucket.section,
        bucket.sectionLabel,
        bucket.lineCode,
        bucket.lineName,
        bucket.subjectCode,
        bucket.subjectName,
        currency,
        bucket.category,
        bucket.balanceDirection,
        openingDebit,
        openingCredit,
        openingBalance,
        money(bucket.periodDebit),
        money(bucket.periodCredit),
        periodNet,
        closingDebit,
        closingCredit,
        closingBalance,
        comparisonPeriodNet,
        comparisonClosingBalance,
        money(periodNet.subtract(comparisonPeriodNet)),
        money(closingBalance.subtract(comparisonClosingBalance)),
        bucket.voucherCount,
        bucket.lineCount,
        totalRow,
        drillPath);
  }

  /**
   * 合计行。
   *
   * @param sourceRows 被合计的行（借贷分列与余额都从它们求和）
   * @param periodNetOverride 本期净额的覆盖值（null ⇒ 求和）
   * @param closingBalanceOverride 期末余额的覆盖值（null ⇒ 求和）
   */
  private FinanceReportRow totalRow(
      FinancialReportType type,
      String lineName,
      String section,
      String sectionLabel,
      String drillSection,
      String currency,
      List<FinanceReportRow> sourceRows,
      BigDecimal periodNetOverride,
      BigDecimal closingBalanceOverride,
      PeriodWindow window,
      PeriodWindow compare) {
    var openingDebit = ZERO;
    var openingCredit = ZERO;
    var periodDebit = ZERO;
    var periodCredit = ZERO;
    var closingDebit = ZERO;
    var closingCredit = ZERO;
    var openingBalance = ZERO;
    var periodNet = ZERO;
    var closingBalance = ZERO;
    long vouchers = 0;
    long lines = 0;
    for (var row : sourceRows) {
      openingDebit = openingDebit.add(row.openingDebit());
      openingCredit = openingCredit.add(row.openingCredit());
      periodDebit = periodDebit.add(row.periodDebit());
      periodCredit = periodCredit.add(row.periodCredit());
      closingDebit = closingDebit.add(row.closingDebit());
      closingCredit = closingCredit.add(row.closingCredit());
      openingBalance = openingBalance.add(row.openingBalance());
      periodNet = periodNet.add(row.periodNet());
      closingBalance = closingBalance.add(row.closingBalance());
      vouchers += row.voucherCount();
      lines += row.lineCount();
    }
    if (periodNetOverride != null) periodNet = periodNetOverride;
    if (closingBalanceOverride != null) closingBalance = closingBalanceOverride;
    var lineCode = "TOTAL:" + drillSection;
    return new FinanceReportRow(
        rowKey(currency, lineCode),
        section,
        sectionLabel,
        lineCode,
        lineName,
        null,
        null,
        currency,
        "",
        "",
        money(openingDebit),
        money(openingCredit),
        money(openingBalance),
        money(periodDebit),
        money(periodCredit),
        money(periodNet),
        money(closingDebit),
        money(closingCredit),
        money(closingBalance),
        ZERO,
        ZERO,
        ZERO,
        ZERO,
        vouchers,
        lines,
        true,
        drillPathOf(type, lineCode, null, currency, window, compare));
  }

  /** 真实可走的钻取路径。 */
  private String drillPath(
      FinancialReportType type,
      Bucket bucket,
      String currency,
      PeriodWindow window,
      PeriodWindow compare) {
    return drillPathOf(type, bucket.lineCode, bucket.subjectCode, currency, window, compare);
  }

  /** 真实可走的钻取路径（显式行码 + 科目）。 */
  private String drillPathOf(
      FinancialReportType type,
      String lineCode,
      String subjectCode,
      String currency,
      PeriodWindow window,
      PeriodWindow compare) {
    var sb = new StringBuilder();
    sb.append("/finance/financial-reports/drill?reportType=").append(type.name());
    sb.append("&lineCode=").append(encode(lineCode));
    if (subjectCode != null && !subjectCode.isBlank()) {
      sb.append("&subjectCode=").append(encode(subjectCode));
    }
    sb.append("&currency=").append(encode(currency));
    sb.append("&periodFromId=").append(window.fromId());
    sb.append("&periodToId=").append(window.toId());
    if (compare != null) {
      sb.append("&comparePeriodFromId=").append(compare.fromId());
      sb.append("&comparePeriodToId=").append(compare.toId());
    }
    return sb.toString();
  }

  /** 对比期的同一个行（没有则为空）。 */
  private Bucket comparisonBucket(
      Map<String, Agg> comparison,
      Map<String, TemplateView> templates,
      FinancialReportType type,
      String lineCode) {
    if (comparison == null || comparison.isEmpty()) return null;
    Bucket found = null;
    for (var agg : comparison.values()) {
      var target = targetLine(type, agg, templates);
      if (!target.lineCode.equals(lineCode)) continue;
      if (found == null) {
        found = new Bucket();
        found.section = target.section();
        found.sectionLabel = target.sectionLabel();
        found.lineCode = target.lineCode();
        found.lineName = target.lineName();
      }
      merge(found, agg);
    }
    return found;
  }

  /** 对比期整段的重算（用于对比期的合计）。 */
  private List<FinanceReportRow> comparisonRows(
      List<FinanceReportRow> rows,
      Map<String, Agg> comparison,
      Map<String, TemplateView> templates,
      FinancialReportType type,
      PeriodWindow window,
      PeriodWindow compare) {
    var result = new ArrayList<FinanceReportRow>();
    for (var row : rows) {
      var bucket = comparisonBucket(comparison, templates, type, row.lineCode());
      if (bucket == null) continue;
      result.add(toRow(bucket, row.currency(), null, type, window, compare, row.totalRow()));
    }
    return result;
  }

  /** 一组行的期末余额合计。 */
  private static BigDecimal sumClosing(List<FinanceReportRow> rows) {
    var sum = ZERO;
    for (var row : rows) {
      sum = sum.add(row.closingBalance());
    }
    return money(sum);
  }

  /** 一组行的本期净额合计。 */
  private static BigDecimal sumPeriodNet(List<FinanceReportRow> rows) {
    var sum = ZERO;
    for (var row : rows) {
      sum = sum.add(row.periodNet());
    }
    return money(sum);
  }

  /** 一组行的合计。 */
  private FinanceReportTotals totalsOf(List<FinanceReportRow> rows) {
    var openingDebit = ZERO;
    var openingCredit = ZERO;
    var periodDebit = ZERO;
    var periodCredit = ZERO;
    var closingDebit = ZERO;
    var closingCredit = ZERO;
    var periodNet = ZERO;
    long vouchers = 0;
    long lines = 0;
    for (var row : rows) {
      if (row.totalRow()) continue;
      openingDebit = openingDebit.add(row.openingDebit());
      openingCredit = openingCredit.add(row.openingCredit());
      periodDebit = periodDebit.add(row.periodDebit());
      periodCredit = periodCredit.add(row.periodCredit());
      closingDebit = closingDebit.add(row.closingDebit());
      closingCredit = closingCredit.add(row.closingCredit());
      periodNet = periodNet.add(row.periodNet());
      vouchers += row.voucherCount();
      lines += row.lineCount();
    }
    return new FinanceReportTotals(
        money(openingDebit),
        money(openingCredit),
        money(openingDebit.subtract(openingCredit)),
        money(periodDebit),
        money(periodCredit),
        money(periodNet),
        money(closingDebit),
        money(closingCredit),
        money(closingDebit.subtract(closingCredit)),
        vouchers,
        lines);
  }

  /** 自检项。 */
  private FinanceReportCheck check(
      String name, BigDecimal left, BigDecimal right, String detail) {
    var difference = money(left.subtract(right));
    return new FinanceReportCheck(
        name,
        difference.compareTo(BigDecimal.ZERO) == 0,
        money(left),
        money(right),
        difference,
        detail);
  }

  // ================================================================ 工具

  private Map<String, TemplateView> templateViews(FinancialReportType type) {
    var result = new LinkedHashMap<String, TemplateView>();
    for (var row : templateRows(type == null ? null : type.name())) {
      if (!row.enabled()) continue;
      result.put(
          row.reportType() + "|" + row.subjectCode(),
          new TemplateView(
              row.reportType(),
              row.subjectCode(),
              row.lineCode(),
              row.lineName(),
              row.section(),
              row.cashFlowGroup(),
              row.cashRole(),
              row.sortOrder()));
    }
    return result;
  }

  /** 模板里标了多少个现金科目（现金流量表的前置条件）。 */
  private static int countCashSubjects(Map<String, TemplateView> templates) {
    var count = 0;
    for (var view : templates.values()) {
      if ("CASH".equals(view.cashRole())) count++;
    }
    return count;
  }

  private List<String> currenciesOf(Map<String, Agg> aggs) {
    var seen = new ArrayList<String>();
    for (var agg : aggs.values()) {
      if (!seen.contains(agg.currency)) seen.add(agg.currency);
    }
    return seen;
  }

  private Map<String, Agg> byCurrency(Map<String, Agg> aggs, String currency) {
    var result = new LinkedHashMap<String, Agg>();
    for (var entry : aggs.entrySet()) {
      if (entry.getValue().currency.equals(currency)) result.put(entry.getKey(), entry.getValue());
    }
    return result;
  }

  /** 是不是损益类科目（利润表口径）。 */
  private static boolean isProfitOrCost(
      Agg agg, Map<String, TemplateView> templates, FinancialReportType type) {
    if (agg.category.equals("PROFIT_LOSS") || agg.category.equals("COST")) return true;
    // 模板显式把它归到利润表 ⇒ 也算（模板优先）。
    return templates.containsKey(type.name() + "|" + agg.subjectCode);
  }

  /**
   * 余额取正负。
   *
   * <p>🔴 符号规则**按段落**而不是按科目：
   * <ul>
   *   <li>资产段：借 − 贷（借正贷负）；</li>
   *   <li>负债 / 权益 / 损益结转 / 收入段：贷 − 借（贷正借负）；</li>
   *   <li>成本费用段：借 − 贷（费用为正）；</li>
   *   <li>科目余额表 / 明细账：按**科目余额方向**取正负。</li>
   * </ul>
   * 负债/权益/损益结转/收入一律用「贷 − 借」，是**会计恒等式**成立的前提：
   * Σ(借 − 贷) ≡ 0 ⇒ Σ资产(借−贷) = Σ负债权益损益(贷−借)。
   */
  private static BigDecimal signedOf(
      FinancialReportType type,
      String section,
      String direction,
      BigDecimal debit,
      BigDecimal credit) {
    if (type == FinancialReportType.BALANCE_SHEET || type == FinancialReportType.INCOME_STATEMENT) {
      return switch (section) {
        case "LIABILITY", "EQUITY", "RETAINED", "REVENUE" -> money(credit.subtract(debit));
        default -> money(debit.subtract(credit));
      };
    }
    return signed(debit, credit, direction);
  }

  /** 余额方向取正负：借方科目按借正贷负，贷方科目按贷正借负。 */
  private static BigDecimal signed(BigDecimal debit, BigDecimal credit, String direction) {
    var net = debit.subtract(credit);
    return money("CREDIT".equals(direction) ? net.negate() : net);
  }

  private static BigDecimal money(BigDecimal value) {
    if (value == null) return ZERO;
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? ZERO : value;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String requireText(String raw, String label) {
    if (raw == null || raw.trim().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
    }
    return raw.trim();
  }

  private static FinancialReportType parseReportType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "报表类型不能为空");
    }
    try {
      return FinancialReportType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "报表类型取值不合法：" + raw);
    }
  }

  private static String parseCashRole(String raw) {
    if (raw == null || raw.isBlank()) return "";
    var value = raw.trim().toUpperCase(Locale.ROOT);
    if (!value.equals("CASH") && !value.equals("NON_CASH")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "现金角色取值不合法：" + raw + "（只能是 CASH 或 NON_CASH）");
    }
    return value;
  }

  private static String parseCashGroup(String raw) {
    if (raw == null || raw.isBlank()) return "";
    var value = raw.trim().toUpperCase(Locale.ROOT);
    try {
      return FinancialReportCashFlowGroup.valueOf(value).name();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "现金流活动类别取值不合法：" + raw + "（经营 / 投资 / 筹资）");
    }
  }

  private static String csvCell(String raw) {
    var value = raw == null ? "" : raw;
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
  }

  private static String plain(BigDecimal value) {
    return value == null ? "" : value.toPlainString();
  }

  private static String encode(String raw) {
    return URLEncoder.encode(raw == null ? "" : raw, StandardCharsets.UTF_8);
  }

  private static String rowKey(String currency, String lineCode) {
    return currency + "|" + lineCode;
  }

  private static String sha256(byte[] content) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var bytes = digest.digest(content);
      var sb = new StringBuilder();
      for (var b : bytes) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "内容指纹计算失败");
    }
  }
}
