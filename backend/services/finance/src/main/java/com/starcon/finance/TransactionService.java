package com.starcon.finance;

import static com.starcon.finance.SettlementModels.SettlementCategory;
import static com.starcon.finance.TransactionModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * 12.3.1.2 交易明细。
 *
 * <p>蓝图 12-finance.md：「管理结算单下的逐笔明细：订单销售、退款、费用（佣金/仓储/配送/广告）、赔付、调整、代扣税，
 * 保留外部流水号与币种；<b>允许无订单行的费用类明细</b>。」输入：「平台交易报告解析结果；<code>idempotency_key</code>。」
 * 输出：「明细行 + <b>汇总校验（明细合计 = 结算单汇总）</b>。」 加粗句：「<b>不强制每行都有订单</b>是硬约束——
 * 佣金、罚款、月度调整天然无订单行，硬要匹配会产生大量虚假异常。」
 *
 * <p><b>本模块与 12.3.1.1 的关系（先读 V345 再决定，不是猜的）</b>：
 *
 * <ol>
 *   <li><b>不另建平行的明细表。</b> V345 已经建了 {@code finance_settlement_line}（结算明细行：
 *       平台账单的一行一笔，按结算单挂载、append-only、tenant_id NOT NULL + FORCE RLS）。
 *       它**就是**「结算单下的逐笔明细」这张表 ⇒ 本模块在**同一张表**上用 V348 补齐缺口
 *       （交易类型 / 订单行引用 / 幂等键 / 代扣税类别 / 汇总校验）。
 *       两张平行明细表 ⇒ 结算金额有两个来源 ⇒ 「明细合计 = 结算单汇总」立刻变成两条对不上的账。
 *   <li><b>不改 V345 的一个字</b>（它已进过集成头并被应用过，改内容会让 Flyway checksum 校验炸掉）：
 *       只做 ADD COLUMN + 新增表；唯一一处「改既有约束」是类别词表加一个值 'TAX'（代扣税），
 *       且**明细行与汇总项两张表必须同时放宽**（V345 的派生链按 category 聚合，两边词表要一致）。
 *   <li><b>派生链不打架</b>：本模块**只写明细行**（唯一写入口），汇总项与结算金额仍由
 *       V345 的触发器派生 ⇒ 「明细合计 = 结算单汇总」在结构上恒成立，
 *       本模块的汇总校验是**把它算出来给人看**并逐项核对**不恒成立**的那些东西
 *       （分类口径 / 笔数 / 币种 / 符号 / 周期 / 订单行）。
 * </ol>
 *
 * <p><b>上游没交付就说没交付，绝不造数。</b>
 *
 * <ul>
 *   <li>平台交易报告**自动同步**（上游「卷 02 同步」没有该端点）⇒ {@code PLATFORM_SYNC} 这个来源
 *       **当前不可产生**，服务层拒绝并写明归属模块；<b>人工上传交易报告</b>这条路径是真的能用的。
 *   <li>12.3.2.1 对账任务未交付 ⇒ 本模块只做「明细 + 汇总校验」，**不实现**自动匹配、
 *       **不产出**匹配结果与差异分类。
 *   <li>13 利润未交付 ⇒ 本模块**不产出任何利润数字**。
 * </ul>
 *
 * <p><b>时间字段</b>：{@code created_at} / {@code parsed_at} / {@code checkedAt} 都是 {@code timestamptz}，
 * 一律 OffsetDateTime + {@code ?::timestamptz}；{@code occurred_on} 是 {@code date}（日历日，不是某一瞬间），
 * 一律 {@code ?::date}。凡涉及「今天」的判断一律用 **UTC 日历日**
 * （{@code LocalDate.now(ZoneOffset.UTC)}），不用会话时区。
 */
@Service
public class TransactionService {

  private static final Logger logger = LoggerFactory.getLogger(TransactionService.class);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 幂等键长度上限（与 DB 列宽一致）。 */
  static final int IDEMPOTENCY_KEY_MAX = 200;

  /** 外部流水号长度上限（与 DB 列宽一致）。 */
  static final int EXTERNAL_REFERENCE_MAX = 160;

  /** 订单号长度上限（与 DB 列宽一致）。 */
  static final int ORDER_REFERENCE_MAX = 160;

  private final DSLContext sql;
  private final ObjectMapper json;

  public TransactionService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 本模块的缺口必须分开说：平台交易报告自动同步（卷 02，**别人的**服务）、
   * 自动对账任务（12.3.2.1，**本服务**的后续模块）、利润（13，**别的**服务）——
   * 把三者都写成「不可用」，运维会以为要去催结算，而实际上后两者是本服务内部的事。
   */
  public FinanceTransactionCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceTransactionCapability>();
    var gaps = new ArrayList<FinanceTransactionDownstreamGap>();

    capabilities.add(
        new FinanceTransactionCapability(
            "12.3.1.2",
            "结算单下的逐笔明细",
            true,
            "finance_settlement_line（复用 12.3.1.1 的明细行表，V348 补齐本模块的列）",
            "",
            "明细行**真的**落 PostgreSQL 16，查询走 jOOQ 显式 SQL，全链路 BigDecimal + NUMERIC(19,4)，无浮点。"
                + "**不另建平行的明细表** —— 两张表各写一份金额，结算金额就有两个来源，"
                + "「明细合计 = 结算单汇总」立刻变成两条对不上的账。"));
    capabilities.add(
        new FinanceTransactionCapability(
            "12.3.1.2",
            "交易类型分类（10 类）",
            true,
            "finance_settlement_line.transaction_type + DB 层 CHECK 钉死与汇总类别的配对",
            "",
            "订单销售 / 退款 / 佣金 / FBA 费用 / 仓储费 / 配送费 / 广告费 / 赔付 / 其他调整 / 代扣税。"
                + "交易类型与结算汇总类别的**配对关系由 DB 层 CHECK 钉死** —— "
                + "「明细说仓储费、汇总却算进别的桶」这种错位在结构上进不来。"));
    capabilities.add(
        new FinanceTransactionCapability(
            "12.3.1.2",
            "允许无订单行的费用类明细",
            true,
            "order_reference / order_line_reference **可空**，且没有任何约束要求某类明细必须带订单号",
            "",
            "蓝图加粗句「**不强制每行都有订单**是硬约束——佣金、罚款、月度调整天然无订单行，"
                + "硬要匹配会产生大量虚假异常」的数据层落法：订单列可空、没有指向订单表的跨库 FK、"
                + "**没有**「缺订单即异常」的触发器或校验。"
                + "唯一一条弱约束是「给了订单行号就必须给订单号」——那是数据错误，不是「缺订单」。"
                + "汇总校验里「无订单行的明细笔数」是**正常状态**，不计入异常。"));
    capabilities.add(
        new FinanceTransactionCapability(
            "12.3.1.2",
            "幂等键（idempotency_key）",
            true,
            "finance_settlement_line.idempotency_key + 部分唯一索引 + 跨单占用守卫",
            "",
            "蓝图输入里点名的幂等键：**同一笔平台交易不许记两次**。"
                + "同一张结算单内由部分唯一索引挡；跨单由 BEFORE INSERT 守卫挡"
                + "（作废与被取代的单**不持有**幂等键 ⇒ 「重新解析建新单」这条上游流程不会被挡住）。"));
    capabilities.add(
        new FinanceTransactionCapability(
            "12.3.1.2",
            "人工上传平台交易报告（CSV / JSON）",
            true,
            "POST /finance/transactions/import → 真解析 → 真入库 + 原文与解析版本留痕",
            "",
            "导入路径**真的能落地**：CSV（按 RFC 4180 处理引号）与 JSON 都能解析成明细行并落库。"
                + "文件级错误整体失败（400，一条都不导入）；行级错误只跳过那一行并逐条说明为什么 —— "
                + "「导入 0 条」如果不说清原因，现场会以为文件是空的。"
                + "原文与解析版本落在 finance_settlement_transaction_source_file。"));
    capabilities.add(
        new FinanceTransactionCapability(
            "12.3.1.2",
            "汇总校验（明细合计 = 结算单汇总）",
            true,
            "GET /finance/transactions/reconciliation → 实时算出来的核对结果",
            "",
            "明细合计 = 汇总项合计 = 结算金额在 V345 的 DB 派生链里**结构上**恒成立；"
                + "本模块做的是**把它算出来给人看**，并逐项核对**不恒成立**的那些东西："
                + "分类口径、笔数、币种、符号、结算周期、订单行。"
                + "这是**实时计算**，不是存下来的快照。"));
    capabilities.add(
        new FinanceTransactionCapability(
            "12.3.1.1",
            "结算单（上游）",
            true,
            "finance_settlement / finance_settlement_summary / finance_settlement_line",
            "",
            "上游已交付：明细行挂在结算单下，结算金额由汇总项求和派生（DB 触发器）。"
                + "本模块**只写明细行**，汇总项与结算金额**不碰** —— 派生链只有 V345 那一个写入口。"));
    capabilities.add(
        new FinanceTransactionCapability(
            "卷 02 同步",
            "平台交易报告自动同步",
            false,
            "上游「卷 02 同步」没有交易报告端点（channel 服务只有通用同步游标 / 批次设施）",
            "卷 02 同步",
            "🔴 **平台交易报告自动同步不可用，归属模块 卷 02 同步。**"
                + "服务层拒绝用这个来源登记明细并写明归属模块；词表里的值保留（上游交付后还要能用）。"
                + "**本模块绝不造一份「同步来的」假交易报告顶替它** —— "
                + "人工上传交易报告这条路径是真的能用的。"));
    capabilities.add(
        new FinanceTransactionCapability(
            "12.3.2.1",
            "自动对账任务",
            false,
            "本模块只算汇总校验，不实现自动匹配",
            "12.3.2.1",
            "「对账任务」（选范围、跑匹配、产出匹配结果与差异清单）属 12.3.2.1，**未交付**。"
                + "本模块**不实现**匹配、**不产出**差异分类 —— 那是别的模块的职责。"
                + "匹配要用的键（订单号 + 外部流水号 + 交易类型 + 日期 + 金额）本模块**已经落库**，"
                + "上游交付时直接读这一张表即可，不必再抄一份。"));
    capabilities.add(
        new FinanceTransactionCapability(
            "13.1.1.1",
            "利润与经营分析",
            false,
            "reporting 服务不在本仓（编排闸 serviceBlocked）",
            "13 利润与经营分析",
            "交易明细是它的一处上游输入（已结算 / 未结算是跨境特有的资金口径问题）。"
                + "本模块**不产出任何利润数字**。"));

    gaps.add(
        new FinanceTransactionDownstreamGap(
            "12.3.2.1",
            "对账任务",
            "finance",
            false,
            "12.3 结算与对账",
            "本模块只做「明细 + 汇总校验」；自动匹配与差异分类未交付。"));
    gaps.add(
        new FinanceTransactionDownstreamGap(
            "12.3.1.3",
            "延迟结算",
            "finance",
            false,
            "12.3 结算与对账",
            "在途未结算金额口径属 12.3.1.3，**未交付**；本模块不碰它。"));
    gaps.add(
        new FinanceTransactionDownstreamGap(
            "13.1.1.1",
            "利润与经营分析",
            "analytics",
            false,
            "13 利润与经营分析",
            "13 未交付：交易明细是它的一处上游输入。"));
    gaps.add(
        new FinanceTransactionDownstreamGap(
            "卷 02 同步",
            "平台交易报告同步",
            "channel",
            false,
            "2 渠道与店铺",
            "上游没有交易报告端点 ⇒ 自动同步不可用；人工上传交易报告可用。"));

    return new FinanceTransactionCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinanceTransactionOptions options() {
    var types =
        sql.fetch(
                "select distinct l.transaction_type from finance_settlement_line l"
                    + " join finance_settlement s on s.tenant_id = l.tenant_id and s.id = l.settlement_id"
                    + " order by l.transaction_type")
            .map(r -> r.get(0, String.class));
    var categories =
        sql.fetch("select distinct category from finance_settlement_line order by category")
            .map(r -> r.get(0, String.class));
    var currencies =
        sql.fetch("select distinct currency from finance_settlement_line order by currency")
            .map(r -> r.get(0, String.class));
    var sources =
        sql.fetch("select distinct source from finance_settlement_line order by source")
            .map(r -> r.get(0, String.class));
    var settlements =
        sql.fetch(
                "select distinct s.id, s.settlement_no, s.shop_code, s.shop_name, s.period_start,"
                    + " s.period_end, s.currency, s.status from finance_settlement s"
                    + " join finance_settlement_line l on l.tenant_id = s.tenant_id"
                    + " and l.settlement_id = s.id order by s.period_end desc, s.settlement_no")
            .map(
                r ->
                    new FinanceTransactionSettlementOption(
                        r.get("id", UUID.class),
                        r.get("settlement_no", String.class),
                        r.get("shop_code", String.class),
                        r.get("shop_name", String.class),
                        r.get("period_start", LocalDate.class),
                        r.get("period_end", LocalDate.class),
                        r.get("currency", String.class),
                        r.get("status", String.class)));
    var platforms =
        sql.fetch(
                "select distinct s.platform_code from finance_settlement s"
                    + " join finance_settlement_line l on l.tenant_id = s.tenant_id"
                    + " and l.settlement_id = s.id order by s.platform_code")
            .map(r -> r.get(0, String.class));
    return new FinanceTransactionOptions(
        types, categories, currencies, sources, settlements, platforms);
  }

  // ================================================================ 列表

  /**
   * 明细行列表：真分页 + 真筛选 + 真排序 + 汇总。
   *
   * <p>⚠️ **汇总覆盖的是整个筛选结果集，不是当前页**：分页一变汇总就变的话，
   * 「翻到第二页看到总额变小了」会被读成「数据丢了」。
   */
  public FinanceTransactionPage page(FinanceTransactionQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from finance_settlement_line l"
                    + " join finance_settlement s on s.tenant_id = l.tenant_id and s.id = l.settlement_id"
                    + clause,
                binds.toArray())
            .get(0, Long.class);
    List<FinanceTransactionRow> items =
        sql.fetch(
                "select l.*, s.settlement_no, s.shop_code, s.shop_name, s.platform_code,"
                    + " s.period_start, s.period_end from finance_settlement_line l"
                    + " join finance_settlement s on s.tenant_id = l.tenant_id and s.id = l.settlement_id"
                    + clause
                    + " order by s.period_end desc, l.settlement_id, l.line_no limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::row);

    return new FinanceTransactionPage(
        items, p, s, total, summaryByCurrency(where, binds), summaryByType(where, binds));
  }

  private void applyFilters(
      FinanceTransactionQuery q, List<String> where, List<Object> binds) {
    if (q.settlementId() != null) {
      where.add("l.settlement_id = ?");
      binds.add(q.settlementId());
    }
    if (q.shopId() != null) {
      where.add("s.shop_id = ?");
      binds.add(q.shopId());
    }
    if (q.transactionType() != null) {
      where.add("l.transaction_type = ?");
      binds.add(q.transactionType().name());
    }
    if (q.category() != null) {
      where.add("l.category = ?");
      binds.add(q.category().name());
    }
    if (q.currency() != null && !q.currency().isBlank()) {
      where.add("l.currency = ?");
      binds.add(q.currency().trim().toUpperCase(Locale.ROOT));
    }
    if (q.source() != null) {
      where.add("l.source = ?");
      binds.add(q.source().name());
    }
    if (q.platformCode() != null && !q.platformCode().isBlank()) {
      where.add("s.platform_code = ?");
      binds.add(q.platformCode().trim());
    }
    if (q.occurredFrom() != null) {
      where.add("l.occurred_on >= ?::date");
      binds.add(q.occurredFrom());
    }
    if (q.occurredTo() != null) {
      where.add("l.occurred_on <= ?::date");
      binds.add(q.occurredTo());
    }
    if (q.hasOrder() != null) {
      where.add(q.hasOrder() ? "l.order_reference is not null" : "l.order_reference is null");
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add(
          "(l.external_reference ilike ? or l.order_reference ilike ?"
              + " or l.order_line_reference ilike ? or l.description ilike ?"
              + " or l.idempotency_key ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  /**
   * 按币种的汇总（**不跨币种相加**：USD 与 EUR 加起来不是任何一个金额）。
   *
   * <p>⚠️ 作废与被取代的结算单下的明细**不计入**：这两张单在资金口径上不存在，
   * 把它们算进「在途交易金额」会让余额虚高。
   */
  private List<FinanceTransactionCurrencySummary> summaryByCurrency(
      List<String> where, List<Object> binds) {
    var clauses = new ArrayList<String>();
    clauses.add("s.status <> 'VOID'");
    clauses.add("s.status <> 'SUPERSEDED'");
    clauses.addAll(where);
    return sql.fetch(
            "select l.currency, sum(l.amount) as amount, count(*) as cnt,"
                + " count(*) filter (where l.order_reference is null) as orderless_count"
                + " from finance_settlement_line l"
                + " join finance_settlement s on s.tenant_id = l.tenant_id and s.id = l.settlement_id"
                + " where "
                + String.join(" and ", clauses)
                + " group by l.currency order by l.currency",
            binds.toArray())
        .map(
            r ->
                new FinanceTransactionCurrencySummary(
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("cnt", Long.class),
                    r.get("orderless_count", Long.class)));
  }

  /** 按交易类型的汇总。 */
  private List<FinanceTransactionTypeSummary> summaryByType(
      List<String> where, List<Object> binds) {
    var clauses = new ArrayList<String>();
    clauses.add("s.status <> 'VOID'");
    clauses.add("s.status <> 'SUPERSEDED'");
    clauses.addAll(where);
    return sql.fetch(
            "select l.transaction_type, l.category, sum(l.amount) as amount, count(*) as cnt,"
                + " count(*) filter (where l.order_reference is null) as orderless_count"
                + " from finance_settlement_line l"
                + " join finance_settlement s on s.tenant_id = l.tenant_id and s.id = l.settlement_id"
                + " where "
                + String.join(" and ", clauses)
                + " group by l.transaction_type, l.category order by l.transaction_type",
            binds.toArray())
        .map(
            r -> {
              var type = FinanceTransactionType.valueOf(r.get("transaction_type", String.class));
              var category = SettlementCategory.valueOf(r.get("category", String.class));
              return new FinanceTransactionTypeSummary(
                  type,
                  type.label(),
                  category,
                  category.label(),
                  r.get("amount", BigDecimal.class),
                  r.get("cnt", Long.class),
                  r.get("orderless_count", Long.class));
            });
  }

  // ================================================================ 详情

  /** 一条明细的详情：明细本身 + 它所属结算单的当前状态与汇总项。 */
  public FinanceTransactionDetail detail(UUID id) {
    var record =
        sql.fetchOne(
            "select l.*, s.settlement_no, s.shop_code, s.shop_name, s.platform_code,"
                + " s.period_start, s.period_end, s.status as settlement_status,"
                + " s.settlement_amount as settlement_amount,"
                + " s.received_amount as settlement_received_amount"
                + " from finance_settlement_line l"
                + " join finance_settlement s on s.tenant_id = l.tenant_id and s.id = l.settlement_id"
                + " where l.id = ?",
            id);
    if (record == null) throw notFound("交易明细 " + id + " 不存在或无权查看");
    var summary =
        sql.fetch(
                "select category, amount, line_count from finance_settlement_summary"
                    + " where settlement_id = ? order by category",
                record.get("settlement_id", UUID.class))
            .map(
                r -> {
                  var category = SettlementCategory.valueOf(r.get("category", String.class));
                  return new FinanceTransactionSummaryRow(
                      category,
                      category.label(),
                      r.get("amount", BigDecimal.class),
                      r.get("line_count", Long.class));
                });
    return new FinanceTransactionDetail(
        row(record),
        record.get("settlement_status", String.class),
        record.get("settlement_amount", BigDecimal.class),
        record.get("settlement_received_amount", BigDecimal.class),
        summary);
  }

  // ================================================================ 汇总校验

  /**
   * 汇总校验：<b>明细合计 = 结算单汇总</b>。
   *
   * <p>⚠️ **实时算出来的，不是存下来的快照。**
   *
   * <p>「明细合计 = 汇总项合计 = 结算金额」在 12.3.1.1 的 DB 派生链里**结构上**恒成立；
   * 本方法做的是把它算出来给人看，并**逐项核对那些不恒成立的东西**：
   *
   * <ul>
   *   <li><b>分类口径</b>：每个类别的明细合计 vs 汇总项金额；
   *   <li><b>笔数</b>：每个类别的明细笔数 vs 汇总项的 line_count；
   *   <li><b>币种</b>：明细行币种是否都等于结算单币种（混币种会把合计算成废数）；
   *   <li><b>符号</b>：归一后的贡献符号是否与类别约定一致（退款为正 = 归一被绕过）；
   *   <li><b>周期</b>：明细行日期是否落在结算周期内（跨月边界最容易差一天）；
   *   <li><b>订单行</b>：多少笔明细没有订单行（费用类天然如此，**是正常状态不是异常**）。
   * </ul>
   */
  public FinanceTransactionReconciliation reconciliation(UUID settlementId) {
    var head = sql.fetchOne("select * from finance_settlement where id = ?", settlementId);
    if (head == null) throw notFound("结算单 " + settlementId + " 不存在或无权查看");
    String currency = head.get("currency", String.class);
    LocalDate periodStart = head.get("period_start", LocalDate.class);
    LocalDate periodEnd = head.get("period_end", LocalDate.class);
    BigDecimal settlementAmount = head.get("settlement_amount", BigDecimal.class);

    // 明细行侧：按类别求和 + 各项计数。
    var lineByCategory = new LinkedHashMap<SettlementCategory, BigDecimal>();
    var lineCountByCategory = new LinkedHashMap<SettlementCategory, Long>();
    for (var r :
        sql.fetch(
            "select category, sum(amount) as amount, count(*) as cnt"
                + " from finance_settlement_line where settlement_id = ? group by category"
                + " order by category",
            settlementId)) {
      var category = SettlementCategory.valueOf(r.get("category", String.class));
      lineByCategory.put(category, r.get("amount", BigDecimal.class));
      lineCountByCategory.put(category, r.get("cnt", Long.class));
    }

    // 汇总项侧（DB 派生）。
    var summaryByCategory = new LinkedHashMap<SettlementCategory, BigDecimal>();
    var summaryCountByCategory = new LinkedHashMap<SettlementCategory, Long>();
    for (var r :
        sql.fetch(
            "select category, amount, line_count from finance_settlement_summary"
                + " where settlement_id = ?",
            settlementId)) {
      var category = SettlementCategory.valueOf(r.get("category", String.class));
      summaryByCategory.put(category, r.get("amount", BigDecimal.class));
      summaryCountByCategory.put(category, r.get("line_count", Long.class));
    }

    // 逐类别核对。
    var categories = new ArrayList<FinanceTransactionCategoryCheck>();
    var allCategories = new java.util.TreeSet<SettlementCategory>();
    allCategories.addAll(lineByCategory.keySet());
    allCategories.addAll(summaryByCategory.keySet());
    boolean balanced = true;
    for (var category : allCategories) {
      BigDecimal lineAmount = lineByCategory.getOrDefault(category, BigDecimal.ZERO);
      BigDecimal summaryAmount = summaryByCategory.getOrDefault(category, BigDecimal.ZERO);
      long lineCount = lineCountByCategory.getOrDefault(category, 0L);
      long summaryCount = summaryCountByCategory.getOrDefault(category, 0L);
      boolean matched =
          lineAmount.compareTo(summaryAmount) == 0 && lineCount == summaryCount;
      if (!matched) balanced = false;
      categories.add(
          new FinanceTransactionCategoryCheck(
              category, category.label(), lineAmount, summaryAmount, lineCount, summaryCount, matched));
    }

    // 明细行侧的各项计数（币种 / 符号 / 周期 / 订单行）。
    var stats =
        sql.fetchOne(
            "select coalesce(sum(amount),0) as line_total,"
                + " count(*) as line_count,"
                + " count(*) filter (where order_reference is null) as orderless_count,"
                + " count(*) filter (where order_reference is null and category in"
                + " ('COMMISSION','FBA_FEE','ADVERTISING','TAX','ADJUSTMENT')) as orderless_fee_count,"
                + " count(*) filter (where category = 'SALES' and order_reference is null)"
                + " as sales_without_order_count,"
                + " count(*) filter (where currency <> ?) as currency_mismatch_count,"
                + " count(*) filter (where occurred_on is not null and"
                + " (occurred_on < ?::date or occurred_on > ?::date)) as outside_period_count,"
                + " count(*) filter (where (category in ('SALES','COMPENSATION') and amount < 0)"
                + " or (category in ('REFUND','COMMISSION','FBA_FEE','ADVERTISING','TAX')"
                + " and amount > 0)) as sign_violation_count"
                + " from finance_settlement_line where settlement_id = ?",
            currency,
            periodStart,
            periodEnd,
            settlementId);

    BigDecimal lineTotal = stats.get("line_total", BigDecimal.class);
    BigDecimal summaryTotal =
        summaryByCategory.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    if (lineTotal.compareTo(summaryTotal) != 0) balanced = false;
    if (lineTotal.compareTo(settlementAmount) != 0) balanced = false;

    long orderless = stats.get("orderless_count", Long.class);
    long orderlessFee = stats.get("orderless_fee_count", Long.class);
    long salesWithoutOrder = stats.get("sales_without_order_count", Long.class);
    long currencyMismatch = stats.get("currency_mismatch_count", Long.class);
    long outsidePeriod = stats.get("outside_period_count", Long.class);
    long signViolation = stats.get("sign_violation_count", Long.class);

    var issues = new ArrayList<String>();
    for (var check : categories) {
      if (!check.matched()) {
        issues.add(
            "类别 "
                + check.categoryLabel()
                + " 对不上：明细合计 "
                + check.lineAmount().toPlainString()
                + " / 汇总项 "
                + check.summaryAmount().toPlainString()
                + "，笔数 "
                + check.lineCount()
                + " / "
                + check.summaryLineCount());
      }
    }
    if (currencyMismatch > 0) {
      issues.add(
          "有 "
              + currencyMismatch
              + " 行明细的币种不是结算单币种（"
              + currency
              + "）：混币种的合计不是任何一个金额");
    }
    if (signViolation > 0) {
      issues.add(
          "有 "
              + signViolation
              + " 行明细的符号与类别约定不一致（退款/佣金/费用/广告/代扣税应为负，销售额/赔付应为正）");
    }
    if (outsidePeriod > 0) {
      issues.add(
          "有 "
              + outsidePeriod
              + " 行明细的日期落在结算周期（"
              + periodStart
              + " ~ "
              + periodEnd
              + "）之外：跨周期归集最容易在月末边界差一天");
    }
    // ⚠️ 「销售额明细没有订单号」**刻意不进 issues**：
    //   蓝图加粗句是「**不强制每行都有订单**是硬约束 —— 硬要匹配会产生大量虚假异常」。
    //   把「缺订单号」列成问题，正是那条加粗句警告的**虚假异常**。
    //   它作为**计数**报出来（salesWithoutOrderLineCount），供人复核，不当成问题。

    return new FinanceTransactionReconciliation(
        settlementId,
        head.get("settlement_no", String.class),
        head.get("status", String.class),
        currency,
        periodStart,
        periodEnd,
        lineTotal,
        summaryTotal,
        settlementAmount,
        balanced,
        categories,
        stats.get("line_count", Long.class),
        orderless,
        orderlessFee,
        salesWithoutOrder,
        currencyMismatch,
        outsidePeriod,
        signViolation,
        List.copyOf(issues),
        nowUtc());
  }

  // ================================================================ 人工登记

  /**
   * 登记一条交易明细（追加到一张**在册**结算单下）。
   *
   * <p>⚠️ <b>不写汇总项、不写结算金额</b>：那两个是 V345 的派生链写的，本模块碰了就打架。
   *
   * <p>⚠️ <b>「平台交易报告同步」当前不可产生</b>：上游「卷 02 同步」没有这个端点，
   * 服务层拒绝并写明归属模块 —— 不造一份「同步来的」假明细。
   */
  @Transactional
  public FinanceTransactionRow register(
      FinanceTransactionInput input, AccessService.Access actor) {
    if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体为空");
    FinanceTransactionSource source =
        input.source() == null ? FinanceTransactionSource.MANUAL : input.source();
    requireProducibleSource(source);
    var head = requireLiveSettlement(input.settlementId());
    String currency = requireLineCurrency(input.currency(), head.get("currency", String.class));

    var line =
        new FinanceTransactionLineInput(
            input.transactionType(),
            input.amount(),
            input.currency(),
            input.quantity(),
            input.occurredOn(),
            input.externalReference(),
            input.orderReference(),
            input.orderLineReference(),
            input.idempotencyKey(),
            input.description());
    var prepared = prepare(head, line);

    UUID id = UUID.randomUUID();
    int lineNo = nextLineNo(input.settlementId());
    int affected = insertLine(actor.tenantId(), input.settlementId(), lineNo, prepared, source, id);
    if (affected == 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "幂等键 "
              + prepared.idempotencyKey()
              + " 已在结算单 "
              + head.get("settlement_no", String.class)
              + " 下存在：同一笔平台交易不许记两次");
    }
    return one(id);
  }

  /**
   * 批量登记交易明细（同一张结算单）。
   *
   * <p>⚠️ 批量**不**放松任何一条校验：每一行都单独验（金额、币种、幂等键、订单行约束）。
   */
  @Transactional
  public List<FinanceTransactionRow> registerBatch(
      FinanceTransactionBatchInput input, AccessService.Access actor) {
    if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体为空");
    FinanceTransactionSource source =
        input.source() == null ? FinanceTransactionSource.MANUAL : input.source();
    requireProducibleSource(source);
    var head = requireLiveSettlement(input.settlementId());
    if (input.lines() == null || input.lines().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "批量登记必须至少有一条明细：没有明细就没有金额");
    }
    var prepared = new ArrayList<PreparedLine>();
    for (var line : input.lines()) {
      if (line == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批量登记里有空行");
      }
      prepared.add(prepare(head, line));
    }
    int lineNo = nextLineNo(input.settlementId());
    var ids = new ArrayList<UUID>();
    for (var p : prepared) {
      UUID id = UUID.randomUUID();
      int affected = insertLine(actor.tenantId(), input.settlementId(), lineNo, p, source, id);
      if (affected == 0) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "幂等键 "
                + p.idempotencyKey()
                + " 已在结算单 "
                + head.get("settlement_no", String.class)
                + " 下存在：同一笔平台交易不许记两次");
      }
      ids.add(id);
      lineNo++;
    }
    var rows = new ArrayList<FinanceTransactionRow>();
    for (var id : ids) rows.add(one(id));
    return rows;
  }

  // ================================================================ 交易报告导入

  /**
   * 平台交易报告导入（CSV / JSON）：解析 → 校验 → 真实入库 + 原文与解析版本留痕。
   *
   * <p>⚠️ <b>文件级错误整体失败</b>（400，一条都不导入）；<b>行级错误只跳过那一行</b>并逐条写进 warnings。
   *
   * <p>⚠️ <b>幂等</b>：同一份报告里的同一笔交易（同一个 idempotency_key）**重复导入不新建行**，
   * 计入 {@code duplicated}；**幂等键已被另一张在册结算单占用**则整个导入失败（那是一笔交易记两次）。
   */
  @Transactional
  public FinanceTransactionImportResult importReport(
      FinanceTransactionImportInput input, AccessService.Access actor) {
    if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体为空");
    FinanceTransactionSource source =
        input.source() == null ? FinanceTransactionSource.MANUAL_UPLOAD : input.source();
    requireProducibleSource(source);
    var head = requireLiveSettlement(input.settlementId());
    if (input.format() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定导入格式（CSV 或 JSON）");
    }
    String fileKey = blankToNull(input.fileKey());
    if (fileKey == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "必须写明交易报告身份（平台交易报告号 / 上传文件名）：它决定解析版本号怎么递增");
    }
    String content = input.content() == null ? "" : input.content();
    String hash = sha256(content);

    // 解析（文件级错误整体失败；行级错误只跳过那一行）。
    // ⚠️ 每次导入新建一个解析器：解析器把行级警告攒在实例字段上，
    // 复用同一个实例会让**上一次导入的警告漏进这一次的结果**。
    var parsed = new TransactionImportParser(json).parse(input.format(), content);
    var warnings = new ArrayList<>(parsed.warnings());

    // 原文与解析版本留痕（原文 + 指纹 + 版本号）。
    var latest =
        sql.fetchOne(
            "select parse_version from finance_settlement_transaction_source_file"
                + " where file_key = ? order by parse_version desc limit 1",
            fileKey);
    int parseVersion = latest == null ? 1 : latest.get("parse_version", Integer.class) + 1;
    UUID fileId = UUID.randomUUID();
    OffsetDateTime now = nowUtc();
    boolean parsedOk = !parsed.rows().isEmpty();
    sql.execute(
        "insert into finance_settlement_transaction_source_file(id,tenant_id,file_key,file_name,"
            + " content_type,raw_content,content_hash,byte_size,parse_version,parse_status,"
            + " parse_warnings,parsed_at,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?::jsonb,?::timestamptz,?::timestamptz)",
        fileId,
        actor.tenantId(),
        fileKey,
        fileKey,
        input.format().name(),
        content,
        hash,
        content.getBytes(StandardCharsets.UTF_8).length,
        parseVersion,
        parsedOk ? "PARSED" : "REJECTED",
        warningsJson(warnings),
        now,
        now);

    if (!parsedOk) {
      logger.warn("[finance] 交易报告 {} 第 {} 版没有一行可用：{}", fileKey, parseVersion, warnings);
      var rejected =
          sql.fetchOne(
              "select id, file_key, file_name, content_type, content_hash, byte_size, parse_version,"
                  + " parse_status, parse_warnings::text as parse_warnings_text, parsed_at, created_at"
                  + " from finance_settlement_transaction_source_file where id = ?",
              fileId);
      return new FinanceTransactionImportResult(
          parsed.scanned(), 0, 0, parsed.scanned(), parseVersion, sourceFileRow(rejected), warnings);
    }

    int lineNo = nextLineNo(input.settlementId());
    int inserted = 0;
    int duplicated = 0;
    for (var row : parsed.rows()) {
      FinanceTransactionLineInput line =
          new FinanceTransactionLineInput(
              row.type(),
              row.rawAmount(),
              row.currency(),
              row.quantity(),
              row.occurredOn(),
              row.externalReference(),
              row.orderReference(),
              row.orderLineReference(),
              row.idempotencyKey(),
              row.description());
      PreparedLine prepared;
      try {
        prepared = prepare(head, line);
      } catch (ResponseStatusException e) {
        // 行级错误：只跳过这一行，并说明为什么。
        warnings.add("第 " + row.lineNo() + " 行/条 " + e.getReason() + "，已跳过");
        continue;
      }
      UUID id = UUID.randomUUID();
      int affected = insertLine(actor.tenantId(), input.settlementId(), lineNo, prepared, source, id);
      if (affected == 0) {
        // ON CONFLICT DO NOTHING 命中 ⇒ 这把幂等键在这张结算单下已经有了 ⇒ 重复，不是错误。
        // 重复计入 {@code duplicated}（不逐条写 warning：重导一份几百行的报告会刷屏）。
        duplicated++;
        continue;
      }
      lineNo++;
      inserted++;
    }

    if (!warnings.isEmpty()) {
      logger.warn("[finance] 交易报告 {} 有 {} 条未进入：{}", fileKey, warnings.size(), warnings);
    }
    var file =
        sql.fetchOne(
            "select id, file_key, file_name, content_type, content_hash, byte_size, parse_version,"
                + " parse_status, parse_warnings::text as parse_warnings_text, parsed_at, created_at"
                + " from finance_settlement_transaction_source_file where id = ?",
            fileId);
    // ⚠️ skipped 的口径：**解析阶段**拒收的行 + **校验阶段**拒收的行。
    //   解析阶段拒收的行根本没进 parsed.rows()，所以不能靠循环里累加 ——
    //   少算就会让「导入结果说不清有几条没进来」（scanned ≠ imported + duplicated + skipped）。
    int skipped = parsed.scanned() - inserted - duplicated;
    return new FinanceTransactionImportResult(
        parsed.scanned(), inserted, duplicated, skipped, parseVersion, sourceFileRow(file), warnings);
  }

  // ================================================================ 内部工具

  /** 一行明细（已通过校验、已归一）。 */
  private record PreparedLine(
      FinanceTransactionType type,
      SettlementCategory category,
      BigDecimal rawAmount,
      BigDecimal amount,
      String currency,
      BigDecimal quantity,
      LocalDate occurredOn,
      String externalReference,
      String orderReference,
      String orderLineReference,
      String idempotencyKey,
      String description) {}

  /** 校验并归一一行明细。 */
  private PreparedLine prepare(Record head, FinanceTransactionLineInput line) {
    if (line.transactionType() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每一行明细都必须写明交易类型");
    }
    if (line.amount() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每一行明细都必须写明金额");
    }
    BigDecimal raw = money(line.amount());
    if (raw.signum() == 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "金额不能是 0：一笔金额为 0 的明细只会污染笔数口径");
    }
    String currency =
        requireLineCurrency(line.currency(), head.get("currency", String.class));
    BigDecimal quantity = null;
    if (line.quantity() != null) {
      if (line.quantity().signum() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数量必须大于 0");
      }
      quantity = line.quantity().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    }
    String externalReference = limited(blankToNull(line.externalReference()), EXTERNAL_REFERENCE_MAX, "外部流水号");
    String orderReference = limited(blankToNull(line.orderReference()), ORDER_REFERENCE_MAX, "订单号");
    String orderLineReference =
        limited(blankToNull(line.orderLineReference()), ORDER_REFERENCE_MAX, "订单行引用");
    if (orderLineReference != null && orderReference == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "给了订单行引用就必须同时给订单号：「有行号却不知道是哪张订单的行」是数据错误 —— "
              + "注意本模块**不要求**订单号必须存在（费用类明细天然无订单行）");
    }
    String idempotencyKey = limited(blankToNull(line.idempotencyKey()), IDEMPOTENCY_KEY_MAX, "幂等键");
    if (idempotencyKey == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "必须写明幂等键：没有幂等键就没法保证「同一笔平台交易不记两次」");
    }
    return new PreparedLine(
        line.transactionType(),
        line.transactionType().category(),
        raw,
        line.transactionType().normalize(raw),
        currency,
        quantity,
        line.occurredOn(),
        externalReference,
        orderReference,
        orderLineReference,
        idempotencyKey,
        line.description() == null ? "" : line.description());
  }

  /**
   * 写一行明细（唯一写入口：只写明细行，汇总项与结算金额由 V345 的触发器派生）。
   *
   * @return 实际插入的行数（0 = 幂等键已存在，被 {@code ON CONFLICT DO NOTHING} 挡下）
   */
  private int insertLine(
      UUID tenantId,
      UUID settlementId,
      int lineNo,
      PreparedLine line,
      FinanceTransactionSource source,
      UUID id) {
    try {
      return sql.execute(
          "insert into finance_settlement_line(id,tenant_id,settlement_id,line_no,category,"
              + " transaction_type,amount,raw_amount,currency,quantity,occurred_on,"
              + " external_reference,order_reference,order_line_reference,idempotency_key,source,"
              + " description,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?::date,?,?,?,?,?,?,?::timestamptz)"
              + " on conflict do nothing",
          id,
          tenantId,
          settlementId,
          lineNo,
          line.category().name(),
          line.type().name(),
          line.amount(),
          line.rawAmount(),
          line.currency(),
          line.quantity(),
          line.occurredOn(),
          line.externalReference(),
          line.orderReference(),
          line.orderLineReference(),
          line.idempotencyKey(),
          source.name(),
          line.description(),
          nowUtc());
    } catch (org.jooq.exception.DataAccessException e) {
      throw translateDuplicate(e, line.idempotencyKey());
    }
  }

  /**
   * 把「幂等键已存在」翻译成人话。
   *
   * <p>⚠️ jOOQ 把 PG 的报错包成 {@code DataAccessException}，真正的原因（唯一索引 / 守卫触发器）在 cause 链里，
   * 所以这里沿着 cause 链找「幂等键」三个字。
   */
  private static ResponseStatusException translateDuplicate(
      org.jooq.exception.DataAccessException e, String idempotencyKey) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      var message = cause.getMessage();
      if (message != null && message.contains("幂等键")) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
      }
      if (cause.getCause() == cause) break;
    }
    return new ResponseStatusException(
        HttpStatus.CONFLICT,
        "幂等键 "
            + (idempotencyKey == null ? "" : idempotencyKey)
            + " 已被占用：同一笔平台交易不许记两次");
  }

  /**
   * 下一个可用的行号（同一张结算单内连续）。
   *
   * <p>⚠️ 返回的是 {@code max(line_no) + 1}：**不是** max —— 用 max 会与既有行号撞上，
   * 而明细行上有 {@code UNIQUE(tenant_id, settlement_id, line_no)}，撞了就被
   * {@code ON CONFLICT DO NOTHING} 静默跳过（表现为「登记没进去」）。
   */
  private int nextLineNo(UUID settlementId) {
    Long max =
        sql.fetchOne(
                "select coalesce(max(line_no),0) from finance_settlement_line where settlement_id = ?",
                settlementId)
            .get(0, Long.class);
    return (max == null ? 0 : max.intValue()) + 1;
  }

  /** 读回一行明细。 */
  private FinanceTransactionRow one(UUID id) {
    var record =
        sql.fetchOne(
            "select l.*, s.settlement_no, s.shop_code, s.shop_name, s.platform_code,"
                + " s.period_start, s.period_end"
                + " from finance_settlement_line l"
                + " join finance_settlement s on s.tenant_id = l.tenant_id and s.id = l.settlement_id"
                + " where l.id = ?",
            id);
    if (record == null) throw notFound("交易明细 " + id + " 不存在或无权查看");
    return row(record);
  }

  /** 结算单必须存在且**在册**（已到账 / 已作废 / 已被取代的单不许再追加明细）。 */
  private Record requireLiveSettlement(UUID settlementId) {
    if (settlementId == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "必须指定结算单：交易明细挂在结算单下");
    }
    var head = sql.fetchOne("select * from finance_settlement where id = ?", settlementId);
    if (head == null) throw notFound("结算单 " + settlementId + " 不存在或无权操作");
    String status = head.get("status", String.class);
    if ("SETTLED".equals(status)
        || "VOID".equals(status)
        || "SUPERSEDED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "结算单 "
              + head.get("settlement_no", String.class)
              + " 已是终态（"
              + status
              + "），不可再追加明细：账单是外部事实，正确做法是重新解析建新单");
    }
    return head;
  }

  /** 来源必须**当前可产生**：平台交易报告同步的上游（卷 02 同步）未交付该端点。 */
  private static void requireProducibleSource(FinanceTransactionSource source) {
    if (source.producible()) return;
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "「"
            + source.label()
            + "」来源的上游是「卷 02 同步」，当前**未交付**该端点，这批交易明细无法登记。"
            + "请改用「人工上传交易报告」或「人工登记」");
  }

  /**
   * 明细行币种必须等于结算单币种。
   *
   * <p>⚠️ 结算金额是**单币种**的金额：混币种的「明细合计」不是任何一个金额，
   * 「明细合计 = 结算单汇总」这条等式就变成废数。
   */
  private static String requireLineCurrency(String currency, String settlementCurrency) {
    if (currency == null || currency.isBlank()) return settlementCurrency;
    if (!currency.matches("^[A-Za-z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三个字母（如 USD）");
    }
    String value = currency.trim().toUpperCase(Locale.ROOT);
    if (!value.equals(settlementCurrency)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "明细行币种（"
              + value
              + "）必须等于结算单币种（"
              + settlementCurrency
              + "）：结算金额是单币种的金额，混币种的合计不是任何一个金额");
    }
    return value;
  }

  private FinanceTransactionRow row(Record r) {
    var type = FinanceTransactionType.valueOf(r.get("transaction_type", String.class));
    var category = SettlementCategory.valueOf(r.get("category", String.class));
    var source = FinanceTransactionSource.valueOf(r.get("source", String.class));
    return new FinanceTransactionRow(
        r.get("id", UUID.class),
        r.get("settlement_id", UUID.class),
        r.get("settlement_no", String.class),
        r.get("shop_code", String.class),
        r.get("shop_name", String.class),
        r.get("platform_code", String.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("line_no", Integer.class),
        type,
        type.label(),
        category,
        category.label(),
        r.get("amount", BigDecimal.class),
        r.get("raw_amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("quantity", BigDecimal.class),
        r.get("occurred_on", LocalDate.class),
        r.get("external_reference", String.class),
        r.get("order_reference", String.class),
        r.get("order_line_reference", String.class),
        r.get("idempotency_key", String.class),
        source,
        source.label(),
        r.get("description", String.class));
  }

  private FinanceTransactionSourceFileRow sourceFileRow(Record r) {
    String warnings = r.get("parse_warnings_text", String.class);
    List<String> parsed = new ArrayList<>();
    if (warnings != null && !warnings.isBlank()) {
      try {
        parsed.addAll(
            json.readValue(warnings, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
      } catch (Exception e) {
        parsed.add(warnings);
      }
    }
    return new FinanceTransactionSourceFileRow(
        r.get("id", UUID.class),
        r.get("file_key", String.class),
        r.get("file_name", String.class),
        r.get("content_type", String.class),
        r.get("content_hash", String.class),
        r.get("byte_size", Long.class),
        r.get("parse_version", Integer.class),
        r.get("parse_status", String.class),
        parsed,
        r.get("parsed_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static String limited(String value, int max, String label) {
    if (value == null) return null;
    if (value.length() > max) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "最长 " + max + " 个字符（收到 " + value.length() + " 个）");
    }
    return value;
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * 当前时刻（UTC，**截断到微秒**）。
   *
   * <p>⚠️ 截断不是洁癖：Postgres 的 timestamptz 精度就是微秒，
   * 不截断的话写进去的值和读回来的值会在最后一位上不同。
   */
  private static OffsetDateTime nowUtc() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private static String sha256(String content) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /** 告警列表 → JSON 文本（存进 jsonb 列）。 */
  private String warningsJson(List<String> warnings) {
    try {
      return json.writeValueAsString(warnings);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("告警序列化失败", e);
    }
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
