package com.starcon.finance;

import static com.starcon.finance.SettlementModels.*;

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
import java.util.List;
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
 * 12.3.1.1 结算单管理。
 *
 * <p>蓝图 12-finance.md：「管理平台结算单：结算周期、店铺、平台账单汇总（销售额、退款、佣金、FBA 费用、广告费、赔付、其他调整）、
 * 结算金额、到账状态。」输入：「平台结算报告同步（2 同步）；人工上传账单；结算周期配置。」
 * 输出：「结算单 + 汇总项 + 明细行 + 与我方账的差额。」
 * 加粗句：「<b>平台账单是财务事实的重要外部来源，需保留原始文件与解析版本。</b>」
 *
 * <p><b>本模块的五条设计主线</b>：
 *
 * <ol>
 *   <li><b>结算金额是算出来的，不是录进来的。</b> 派生链在 DB 层：
 *       结算明细行 →（触发器）→ 结算汇总项 →（触发器）→ 结算金额。
 *       服务层**没有**写汇总项与结算金额的入口，绕过服务层直连 SQL 也一样会被拒
 *       （V345 的 guard_finance_settlement_summary_derived / _amount_derived）。
 *       ⇒ 「手填一个与明细对不上的总额」在结构上做不到。
 *   <li><b>原始文件与解析版本必须留痕。</b> 每一份上传的账单都落一份
 *       {@code finance_settlement_source_file}（原文 + 内容指纹 + 解析版本号），
 *       结算单通过 {@code source_file_id} 指向**具体哪一次解析**。
 *       同一份账单重解析时版本号**递增**（UNIQUE(tenant_id, file_key, parse_version)）。
 *   <li><b>时间不许静默偏 8 小时。</b> {@code expected_at} / {@code arrived_at} /
 *       {@code parsed_at} 都是 {@code timestamptz}，一律 OffsetDateTime 绑定 +
 *       {@code ?::timestamptz}；写时间的测试要**断言写回的值**。
 *   <li><b>上游没交付就说没交付，绝不造数。</b> 平台结算报告自动同步（卷 02 同步）**没有端点**
 *       ⇒ 「平台结算报告同步」这个来源**当前不可产生**（服务层拒绝并写明归属模块）；
 *       12.3.2.1 对账任务与 13 利润**未交付** ⇒ 本模块只**算差额**，不实现自动对账、不产出利润。
 *   <li><b>差额是真的算出来的。</b> 平台侧金额 vs 我方应收（同店铺、同周期、同币种，
 *       按来源分组）；收款侧按 {@code finance_receipt.settlement_no = 结算单号} 关联 ——
 *       12.2.1.1 放开那条 CHECK 之前这一侧恒为 0，**读路径已经铺好**。
 * </ol>
 */
@Service
public class SettlementService {

  private static final Logger logger = LoggerFactory.getLogger(SettlementService.class);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 来源系统 / 来源流水号的长度上限（与 DB 列宽一致）。 */
  private static final int SOURCE_SYSTEM_MAX = 32;

  private final DSLContext sql;
  private final ObjectMapper json;
  private final ChannelShopPort shops;

  public SettlementService(DSLContext sql, ObjectMapper json, ChannelShopPort shops) {
    this.sql = sql;
    this.json = json;
    this.shops = shops;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 本模块的缺口必须分开说：平台结算报告同步（卷 02，**别人的**服务）、
   * 自动对账任务（12.3.2.1，**本服务**的后续模块）、利润（13，**别的**服务）——
   * 把三者都写成「不可用」，运维会以为要去催结算，而实际上后两者是本服务内部的事。
   */
  public FinanceSettlementCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceSettlementCapability>();
    var gaps = new ArrayList<FinanceSettlementDownstreamGap>();

    capabilities.add(
        new FinanceSettlementCapability(
            "12.3.1.1",
            "结算单 + 汇总项 + 明细行",
            true,
            "finance_settlement / finance_settlement_summary / finance_settlement_line / finance_settlement_event",
            "",
            "结算单已落地：数据落 PostgreSQL 16，查询走 jOOQ 显式 SQL，"
                + "全链路 BigDecimal + NUMERIC(19,4)，无浮点。"
                + "**结算金额由汇总项求和派生**（DB 触发器），服务层与直连 SQL 都写不进一个对不上的总额。"));
    capabilities.add(
        new FinanceSettlementCapability(
            "12.3.1.1",
            "平台账单人工上传（CSV / JSON）",
            true,
            "POST /finance/settlement/import → 真解析 → 真入库 + 原始文件与解析版本留痕",
            "",
            "导入路径**真的能落地**：CSV（按 RFC 4180 处理引号）与 JSON 都能解析成结算单明细行，"
                + "落库后由触发器算出汇总项与结算金额。"
                + "文件级错误整体失败（400，一条都不导入）；行级错误只跳过那一行并逐条说明为什么 —— "
                + "「导入 0 条」如果不说清原因，现场会以为文件是空的。"));
    capabilities.add(
        new FinanceSettlementCapability(
            "12.3.1.1",
            "原始文件与解析版本留存",
            true,
            "finance_settlement_source_file（原文 + 内容指纹 + parse_version）",
            "",
            "蓝图加粗句「平台账单是财务事实的重要外部来源，需保留原始文件与解析版本」的落法："
                + "每次上传都存**原文**与 sha256 指纹；同一份账单重解析时**版本号递增**，"
                + "结算单通过 source_file_id 指向**具体哪一次解析**。"));
    capabilities.add(
        new FinanceSettlementCapability(
            "12.3.1.1",
            "结算周期配置",
            true,
            "finance_settlement_cycle（店铺 → 周期类型 + 到账滞后天数）",
            "",
            "蓝图输入里的「结算周期配置」是**本模块自己的输入**：一家店一份生效配置，"
                + "预计到账时间 = 结算周期结束日 + 到账滞后天数（该日 00:00:00Z）。"));
    capabilities.add(
        new FinanceSettlementCapability(
            "2.3.1.1",
            "店铺主数据（上游 2 渠道与店铺）",
            true,
            "GET /channel/shops（经 ChannelShopPort 授权 HTTP 只读）",
            "",
            "店铺**真的**从上游读：结算单创建时经 channel 的授权 HTTP API 核验店铺并快照"
                + "店名与平台编码。跨服务**只走授权 API**，不直连对方库、不建跨库 FK / 视图。"));
    capabilities.add(
        new FinanceSettlementCapability(
            "卷 02 同步",
            "平台结算报告自动同步",
            false,
            "上游「卷 02 同步」没有结算报告端点（channel 服务只有通用同步游标 / 批次设施）",
            "卷 02 同步",
            "🔴 **平台结算报告自动同步不可用，归属模块 卷 02 同步。**"
                + "服务层拒绝用这个来源建单并写明归属模块；"
                + "词表里的值保留（上游交付后还要能用）。"
                + "**本模块绝不造一份「同步来的」假账单顶替它** —— "
                + "人工上传账单这条路径是真的能用的。"));
    capabilities.add(
        new FinanceSettlementCapability(
            "12.3.2.1",
            "自动对账任务",
            false,
            "本模块只算差额，不实现自动匹配",
            "12.3.2.1",
            "「与我方账的差额」**是真的算出来的**（平台侧金额 vs 我方应收，按来源分组），"
                + "但**自动对账任务**（选范围、跑匹配、产出匹配结果与差异清单）属 12.3.2.1。"
                + "本模块**不实现**匹配、不产出差异分类 —— 那是别的模块的职责。"));
    capabilities.add(
        new FinanceSettlementCapability(
            "13.1.1.1",
            "利润与经营分析",
            false,
            "reporting 服务不在本仓（编排闸 serviceBlocked）",
            "13 利润与经营分析",
            "结算单是它的一处上游输入（已结算 / 未结算是跨境特有的资金口径问题）。"
                + "本模块**不产出任何利润数字**。"));

    gaps.add(
        new FinanceSettlementDownstreamGap(
            "12.3.2.1",
            "对账任务",
            "finance",
            false,
            "12.3 结算与对账",
            "本模块只**算差额**（实时计算，不是快照）；自动匹配与差异分类未交付。"));
    gaps.add(
        new FinanceSettlementDownstreamGap(
            "12.2.1.1",
            "收款单",
            "finance",
            false,
            "12.2 收付款",
            "12.2.1.1 已交付，但它的 settlement_no 由 CHECK 钉死恒为 NULL ⇒ "
                + "收款侧现在关联不上（恒为 0 笔）。"
                + "**接上动作归 12.2.1.1 自己的车道**（DROP 那条 CHECK）；"
                + "本模块的读路径已经铺好（按结算单号关联），上游一放开就自动有数据。"));
    gaps.add(
        new FinanceSettlementDownstreamGap(
            "12.1.1.1",
            "应收单",
            "finance",
            false,
            "12.1 应收",
            "12.1.1.1 已交付，但它当前拒绝 PLATFORM_SETTLEMENT 来源建单 ⇒ "
                + "平台结算口径的应收现在还不存在。"
                + "**接上动作归 12.1.1.1 自己的车道**（放开该来源 + 用结算单号作幂等键）；"
                + "本模块照样拿同店铺同周期的应收算差额，并把结果按来源分组报出来。"));
    gaps.add(
        new FinanceSettlementDownstreamGap(
            "13.1.1.1",
            "利润与经营分析",
            "analytics",
            false,
            "13 利润与经营分析",
            "13 未交付：结算单是它的一处上游输入。"));
    gaps.add(
        new FinanceSettlementDownstreamGap(
            "卷 02 同步",
            "平台结算报告同步",
            "channel",
            false,
            "2 渠道与店铺",
            "上游没有结算报告端点 ⇒ 自动同步不可用；人工上传账单可用。"));

    return new FinanceSettlementCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinanceSettlementOptions options() {
    var statuses =
        sql.fetch("select distinct status from finance_settlement order by status")
            .map(r -> r.get(0, String.class));
    var sources =
        sql.fetch("select distinct source from finance_settlement order by source")
            .map(r -> r.get(0, String.class));
    var currencies =
        sql.fetch("select distinct currency from finance_settlement order by currency")
            .map(r -> r.get(0, String.class));
    var platforms =
        sql.fetch("select distinct platform_code from finance_settlement order by platform_code")
            .map(r -> r.get(0, String.class));
    return new FinanceSettlementOptions(statuses, sources, currencies, platforms, List.of());
  }

  /**
   * 店铺选项（来自上游 channel 2.3.1.1）。
   *
   * <p>⚠️ 取不到就**显式失败**（503），绝不返回空集合冒充「上游没有店铺」。
   */
  public List<FinanceSettlementShopOption> shopOptions(String bearer) {
    var result = new ArrayList<FinanceSettlementShopOption>();
    for (var shop : shops.shops(bearer)) {
      result.add(
          new FinanceSettlementShopOption(
              shop.id(),
              shop.code() == null ? "" : shop.code(),
              shop.name() == null ? "" : shop.name(),
              shop.platformCode() == null ? "" : shop.platformCode(),
              shop.platformName() == null ? "" : shop.platformName(),
              shop.status() == null ? "" : shop.status()));
    }
    return result;
  }

  // ================================================================ 列表

  /**
   * 结算单列表：真分页 + 真筛选 + 真排序。
   *
   * <p>⚠️ **汇总覆盖的是整个筛选结果集，不是当前页**：分页一变汇总就变的话，
   * 「翻到第二页看到总额变小了」会被读成「数据丢了」。
   */
  public FinanceSettlementPage page(SettlementQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from finance_settlement s" + clause, binds.toArray())
            .get(0, Long.class);
    List<FinanceSettlementRow> items =
        sql.fetch(
                "select s.* from finance_settlement s"
                    + clause
                    + " order by s.period_end desc, s.id desc limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::row);

    var summaries = summaryByCurrency(where, binds);
    return new FinanceSettlementPage(items, p, s, total, summaries);
  }

  /**
   * 按币种的汇总。
   *
   * <p>⚠️ **作废与被取代的单不计入**：这两张单在资金口径上不存在，
   * 把它们算进「在途结算金额」会让余额虚高。
   */
  private List<FinanceSettlementCurrencySummary> summaryByCurrency(
      List<String> where, List<Object> binds) {
    var clauses = new ArrayList<String>();
    clauses.add("s.status <> 'VOID'");
    clauses.add("s.status <> 'SUPERSEDED'");
    clauses.addAll(where);
    return sql.fetch(
            "select s.currency,"
                + " sum(s.settlement_amount) as settlement_amount,"
                + " sum(s.received_amount) as received_amount,"
                + " count(*) as cnt,"
                + " count(*) filter (where s.status in ('PENDING','PARTIAL')) as pending_count"
                + " from finance_settlement s where "
                + String.join(" and ", clauses)
                + " group by s.currency order by s.currency",
            binds.toArray())
        .map(
            r ->
                new FinanceSettlementCurrencySummary(
                    r.get("currency", String.class),
                    r.get("settlement_amount", BigDecimal.class),
                    r.get("received_amount", BigDecimal.class),
                    r.get("cnt", Long.class),
                    r.get("pending_count", Long.class)));
  }

  private void applyFilters(SettlementQuery q, List<String> where, List<Object> binds) {
    if (q.status() != null) {
      where.add("s.status = ?");
      binds.add(q.status().name());
    }
    if (q.shopId() != null) {
      where.add("s.shop_id = ?");
      binds.add(q.shopId());
    }
    if (q.currency() != null && !q.currency().isBlank()) {
      where.add("s.currency = ?");
      binds.add(q.currency().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (q.source() != null) {
      where.add("s.source = ?");
      binds.add(q.source().name());
    }
    if (q.platformCode() != null && !q.platformCode().isBlank()) {
      where.add("s.platform_code = ?");
      binds.add(q.platformCode().trim());
    }
    if (q.periodFrom() != null) {
      where.add("s.period_end >= ?::date");
      binds.add(q.periodFrom());
    }
    if (q.periodTo() != null) {
      where.add("s.period_start <= ?::date");
      binds.add(q.periodTo());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add(
          "(s.settlement_no ilike ? or s.shop_name ilike ? or s.shop_code ilike ?"
              + " or s.source_reference ilike ? or s.note ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  // ================================================================ 详情

  /** 结算单详情：单头 + 汇总项 + 明细行 + 事件流 + 原始文件。 */
  public FinanceSettlementDetail detail(UUID id) {
    var head = sql.fetchOne("select * from finance_settlement where id = ?", id);
    if (head == null) throw notFound("结算单 " + id + " 不存在或无权查看");
    var summary =
        sql.fetch(
                "select * from finance_settlement_summary where settlement_id = ?"
                    + " order by case category"
                    + " when 'SALES' then 1 when 'REFUND' then 2 when 'COMMISSION' then 3"
                    + " when 'FBA_FEE' then 4 when 'ADVERTISING' then 5 when 'COMPENSATION' then 6"
                    + " else 7 end, category",
                id)
            .map(this::summaryRow);
    var lines =
        sql.fetch(
                "select * from finance_settlement_line where settlement_id = ? order by line_no", id)
            .map(this::lineRow);
    var events =
        sql.fetch(
                "select * from finance_settlement_event where settlement_id = ? order by occurred_at, id",
                id)
            .map(
                r ->
                    new FinanceSettlementEventRow(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("reason", String.class),
                        r.get("actor_id", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class)));
    FinanceSettlementSourceFileRow sourceFile = null;
    var fileId = head.get("source_file_id", UUID.class);
    if (fileId != null) {
      var file =
          sql.fetchOne(
              "select id, file_key, file_name, content_type, content_hash, byte_size, parse_version,"
                  + " parse_status, parse_warnings::text as parse_warnings_text, parsed_at, created_at"
                  + " from finance_settlement_source_file where id = ?",
              fileId);
      if (file != null) sourceFile = sourceFileRow(file);
    }
    return new FinanceSettlementDetail(row(head), summary, lines, events, sourceFile);
  }

  // ================================================================ 差额

  /**
   * 「与我方账的差额」：**实时算出来**的，不是存下来的快照。
   *
   * <p>⚠️ **不做「金额相同即匹配」**（卷 12 硬约束）：收款侧**只**按结算单号关联。
   */
  public FinanceSettlementDifference difference(UUID id) {
    var head = sql.fetchOne("select * from finance_settlement where id = ?", id);
    if (head == null) throw notFound("结算单 " + id + " 不存在或无权查看");

    UUID shopId = head.get("shop_id", UUID.class);
    String currency = head.get("currency", String.class);
    LocalDate periodStart = head.get("period_start", LocalDate.class);
    LocalDate periodEnd = head.get("period_end", LocalDate.class);
    BigDecimal platformAmount = head.get("settlement_amount", BigDecimal.class);
    BigDecimal platformReceived = head.get("received_amount", BigDecimal.class);

    // 我方应收侧：同店铺、同结算周期、同币种，按来源分组。
    // ⚠️ 周期比较用 confirmed_at 的 **UTC 日期**：账期是日历天，不是某一瞬间。
    var breakdown =
        sql.fetch(
                "select r.source, count(*) as cnt, sum(r.amount) as amount,"
                    + " sum(r.amount - r.settled_amount) as open_amount"
                    + " from finance_receivable r"
                    + " where r.shop_id = ?"
                    + " and r.status in ('OPEN','PARTIALLY_SETTLED','SETTLED') and r.currency = ?"
                    + " and (r.confirmed_at at time zone 'UTC')::date >= ?::date"
                    + " and (r.confirmed_at at time zone 'UTC')::date <= ?::date"
                    + " group by r.source order by r.source",
                shopId,
                currency,
                periodStart,
                periodEnd)
            .map(
                r ->
                    new FinanceSettlementReceivableBreakdown(
                        r.get("source", String.class),
                        r.get("cnt", Long.class),
                        r.get("amount", BigDecimal.class),
                        r.get("open_amount", BigDecimal.class)));

    BigDecimal receivableAmount = BigDecimal.ZERO;
    BigDecimal receivableOpen = BigDecimal.ZERO;
    long receivableCount = 0;
    for (var row : breakdown) {
      receivableAmount = receivableAmount.add(row.amount());
      receivableOpen = receivableOpen.add(row.openAmount());
      receivableCount += row.count();
    }

    // 我方收款侧：按结算单号关联（12.2.1.1 的 settlement_no 现在恒为 NULL ⇒ 恒为 0）。
    var receipt =
        sql.fetchOne(
            "select count(*) as cnt, coalesce(sum(amount),0) as amount from finance_receipt"
                + " where settlement_no = ? and status <> 'VOID'",
            head.get("settlement_no", String.class));
    long receiptCount = receipt.get("cnt", Long.class);
    BigDecimal receiptAmount = receipt.get("amount", BigDecimal.class);

    return new FinanceSettlementDifference(
        id,
        head.get("settlement_no", String.class),
        currency,
        periodStart,
        periodEnd,
        platformAmount,
        platformReceived,
        receivableAmount,
        receivableOpen,
        receivableCount,
        breakdown,
        platformAmount.subtract(receivableOpen),
        receiptCount,
        receiptAmount,
        "收款侧按 `finance_receipt.settlement_no = 结算单号` 关联。"
            + "12.2.1.1 的 settlement_no 目前由 CHECK 钉死恒为 NULL ⇒ 这一侧现在恒为 0 笔；"
            + "**接上动作归 12.2.1.1 自己的车道**（DROP 那条 CHECK 并允许以结算单号建收款）。"
            + "本模块的读路径已经铺好，上游一放开就自动有数据 —— 这里**不猜**、不按金额匹配。",
        "自动对账任务（选范围、跑匹配、产出匹配结果与差异清单）属 12.3.2.1，**未交付**。"
            + "本模块只算差额，不做匹配、不产出差异分类。",
        nowUtc());
  }

  // ================================================================ 手工登记

  /**
   * 手工登记一张结算单（连明细行一起）。
   *
   * <p>⚠️ **结算金额不由这里写**：只写明细行，汇总项与结算金额由 DB 触发器派生。
   *
   * <p>⚠️ <b>「平台结算报告同步」当前不可产生</b>：上游「卷 02 同步」没有这个端点，
   * 服务层拒绝并写明归属模块 —— 不造一份「同步来的」假账单。
   */
  @Transactional
  public FinanceSettlementRow create(
      FinanceSettlementInput input, AccessService.Access actor, String bearer) {
    SettlementSource source = input.source() == null ? SettlementSource.MANUAL : input.source();
    if (!source.producible()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "「"
              + source.label()
              + "」来源的上游是「卷 02 同步」，当前**未交付**该端点，这张结算单无法产生。"
              + "请改用「人工上传账单」或「人工登记」");
    }
    String currency = requireCurrency(input.currency());
    var period = requirePeriod(input.periodStart(), input.periodEnd());
    var shop = requireShop(input.shopId(), bearer);
    String sourceSystem = requireSourceSystem(input.sourceSystem());
    String sourceReference = blankToNull(input.sourceReference());
    if (sourceReference == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "必须写明结算报告号（来源流水号）：它与来源系统组成幂等键，"
              + "只写来源系统不写报告号，同一份报告会被登记两次");
    }
    var lines = requireLines(input.lines());

    UUID id = UUID.randomUUID();
    OffsetDateTime now = nowUtc();
    var cycle = cycleOf(shop.id());
    sql.execute(
        "insert into finance_settlement(id,tenant_id,settlement_no,shop_id,shop_code,shop_name,"
            + " platform_code,period_start,period_end,currency,settlement_amount,status,expected_at,"
            + " arrived_at,received_amount,source,source_system,source_reference,source_file_id,"
            + " parse_version,cycle_id,note,version,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?::date,?::date,?,0,'PENDING',?::timestamptz,null,0,?,?,?,null,1,?,?,0,"
            + "?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        nextReference("SETL"),
        shop.id(),
        shop.code(),
        shop.name(),
        shop.platformCode(),
        period.start(),
        period.end(),
        currency,
        expectedAt(period.end(), cycle),
        source.name(),
        sourceSystem,
        sourceReference,
        cycle == null ? null : cycle.get("id", UUID.class),
        input.note() == null ? "" : input.note(),
        now,
        now);
    insertManualLines(actor.tenantId(), id, currency, lines);
    writeEvent(actor.tenantId(), id, "CREATED", "", "PENDING", "手工登记", actor.userId(), now);
    return detail(id).settlement();
  }

  // ================================================================ 平台账单导入

  /**
   * 平台账单导入（CSV / JSON）：解析 → 校验 → 真实入库 + 原始文件与解析版本留痕。
   *
   * <p>⚠️ <b>文件级错误整体失败</b>（400，一条都不导入）；<b>行级错误只跳过那一行</b>并逐条写进 warnings。
   *
   * <p>⚠️ <b>解析版本递增</b>：同一份账单（file_key）每重新解析一次，parse_version +1；
   * 上一版结算单被置为 SUPERSEDED（**旧单留在库里**，它是「当时登记过」的留痕），
   * 新单指向新的解析版本 ⇒ 结算单可追溯到「哪个文件的哪个版本的哪一次解析」。
   *
   * <p>⚠️ <b>幂等</b>：同一份账单**同样的内容**重复上传 ⇒ 幂等命中，**不新建**结算单。
   */
  @Transactional
  public FinanceSettlementImportResult importBill(
      FinanceSettlementImportInput input, AccessService.Access actor, String bearer) {
    SettlementSource source =
        input.source() == null ? SettlementSource.MANUAL_UPLOAD : input.source();
    if (!source.producible()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "「"
              + source.label()
              + "」来源的上游是「卷 02 同步」，当前**未交付**该端点，这批账单无法登记。"
              + "请改用「人工上传账单」");
    }
    if (input.format() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定导入格式（CSV 或 JSON）");
    }
    String sourceSystem = requireSourceSystem(input.sourceSystem());
    String fileKey = blankToNull(input.fileKey());
    if (fileKey == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "必须写明账单身份（平台结算报告号 / 上传文件名）：它决定解析版本号怎么递增");
    }
    String currency = requireCurrency(input.currency());
    var period = requirePeriod(input.periodStart(), input.periodEnd());
    var shop = requireShop(input.shopId(), bearer);

    String content = input.content() == null ? "" : input.content();
    String hash = sha256(content);

    // 1) 同一份账单的**最新**解析版本。
    var latest =
        sql.fetchOne(
            "select * from finance_settlement_source_file where file_key = ?"
                + " order by parse_version desc limit 1",
            fileKey);

    // 2) 幂等命中：同样的内容已经解析过，而且那一版产出了在册结算单 ⇒ 不新建。
    if (latest != null
        && hash.equals(latest.get("content_hash", String.class))
        && "PARSED".equals(latest.get("parse_status", String.class))) {
      var existing =
          sql.fetchOne(
              "select * from finance_settlement where source_file_id = ? and status <> 'SUPERSEDED'",
              latest.get("id", UUID.class));
      if (existing != null) {
        var duplicate = detail(existing.get("id", UUID.class)).settlement();
        return new FinanceSettlementImportResult(
            0, 0, 0, true, latest.get("parse_version", Integer.class), duplicate, List.of());
      }
    }

    // 3) 解析版本号：同一份账单 +1。
    int parseVersion = latest == null ? 1 : latest.get("parse_version", Integer.class) + 1;

    // 4) 解析（文件级错误整体失败；行级错误只跳过那一行）。
    //    ⚠️ 每次导入新建一个解析器：解析器把行级警告攒在实例字段上，
    //    复用同一个实例会让**上一次导入的警告漏进这一次的结果**。
    var parsed = new SettlementImportParser(json).parse(input.format(), content);
    var warnings = new ArrayList<>(parsed.warnings());

    // 5) 原始文件与解析版本留痕（原文 + 指纹 + 版本号）。
    UUID fileId = UUID.randomUUID();
    OffsetDateTime now = nowUtc();
    boolean parsedOk = !parsed.rows().isEmpty();
    sql.execute(
        "insert into finance_settlement_source_file(id,tenant_id,file_key,file_name,content_type,"
            + " raw_content,content_hash,byte_size,parse_version,parse_status,parse_warnings,"
            + " parsed_at,created_at)"
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
      // 一行都没通过 ⇒ 不建结算单（没有明细行就没有金额）。
      // 但**原始文件仍然留痕**（parse_status='REJECTED' + 逐条告警），现场能查「当时传了什么」。
      logger.warn("[finance] 平台账单 {} 第 {} 版没有一行可用：{}", fileKey, parseVersion, warnings);
      return new FinanceSettlementImportResult(
          parsed.scanned(), 0, parsed.scanned(), false, parseVersion, null, warnings);
    }

    // 6) 同一份账单的上一版结算单 ⇒ 置为 SUPERSEDED（**不删**：它是留痕）。
    var previous =
        sql.fetchOne(
            "select * from finance_settlement where source_system = ? and source_reference = ?"
                + " and status <> 'SUPERSEDED'",
            sourceSystem,
            fileKey);
    if (previous != null) {
      UUID previousId = previous.get("id", UUID.class);
      sql.execute(
          "update finance_settlement set status='SUPERSEDED', updated_at=?::timestamptz,"
              + " version=version+1 where id = ?",
          now,
          previousId);
      writeEvent(
          actor.tenantId(),
          previousId,
          "SUPERSEDED",
          previous.get("status", String.class),
          "SUPERSEDED",
          "被同一份账单的第 " + parseVersion + " 版解析取代",
          actor.userId(),
          now);
    }

    // 7) 建新结算单 + 写明细行（汇总项与结算金额由 DB 触发器派生）。
    UUID id = UUID.randomUUID();
    var cycle = cycleOf(shop.id());
    sql.execute(
        "insert into finance_settlement(id,tenant_id,settlement_no,shop_id,shop_code,shop_name,"
            + " platform_code,period_start,period_end,currency,settlement_amount,status,expected_at,"
            + " arrived_at,received_amount,source,source_system,source_reference,source_file_id,"
            + " parse_version,cycle_id,note,version,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?::date,?::date,?,0,'PENDING',?::timestamptz,null,0,?,?,?,?,?,?,?,0,"
            + "?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        nextReference("SETL"),
        shop.id(),
        shop.code(),
        shop.name(),
        shop.platformCode(),
        period.start(),
        period.end(),
        currency,
        expectedAt(period.end(), cycle),
        source.name(),
        sourceSystem,
        fileKey,
        fileId,
        parseVersion,
        cycle == null ? null : cycle.get("id", UUID.class),
        input.note() == null ? "" : input.note(),
        now,
        now);
    insertImportedLines(actor.tenantId(), id, currency, parsed.rows());
    writeEvent(
        actor.tenantId(),
        id,
        previous == null ? "IMPORTED" : "REPARSED",
        "",
        "PENDING",
        "平台账单导入（"
            + fileKey
            + " 第 "
            + parseVersion
            + " 版，共 "
            + parsed.rows().size()
            + " 行）",
        actor.userId(),
        now);

    if (!warnings.isEmpty()) {
      // 警告不是秘密：它要出现在导入结果里，让人知道「有几条没进来」。
      logger.warn("[finance] 平台账单 {} 有 {} 条未进入：{}", fileKey, warnings.size(), warnings);
    }
    return new FinanceSettlementImportResult(
        parsed.scanned(),
        parsed.rows().size(),
        parsed.scanned() - parsed.rows().size(),
        false,
        parseVersion,
        detail(id).settlement(),
        warnings);
  }

  // ================================================================ 到账登记

  /**
   * 登记到账：写实际到账时间与到账金额，并推进到账状态。
   *
   * <p>⚠️ <b>到账时间必填</b>：默认填当前时刻会把「上周到的钱」记成今天到。
   */
  @Transactional
  public FinanceSettlementRow recordArrival(
      UUID id, FinanceSettlementArrivalInput input, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_settlement where id = ?", id);
    if (head == null) throw notFound("结算单 " + id + " 不存在或无权操作");
    String status = head.get("status", String.class);
    if (SettlementStatus.valueOf(status).terminal()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "结算单已是终态（" + status + "），不可再改");
    }
    if (input == null || input.arrivedAt() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "必须写明实际到账时间：默认填当前时刻会把「上周到的钱」记成今天到");
    }
    BigDecimal settlementAmount = head.get("settlement_amount", BigDecimal.class);
    BigDecimal received = requireReceived(input.receivedAmount());
    String next = received.compareTo(settlementAmount) >= 0 ? "SETTLED" : "PARTIAL";
    OffsetDateTime now = nowUtc();
    sql.execute(
        "update finance_settlement set status=?, arrived_at=?::timestamptz, received_amount=?,"
            + " updated_at=?::timestamptz, version=version+1"
            + " where id = ?",
        next,
        input.arrivedAt(),
        received,
        now,
        id);
    writeEvent(
        actor.tenantId(),
        id,
        "ARRIVED",
        status,
        next,
        "到账 " + received.toPlainString() + "（结算金额 " + settlementAmount.toPlainString() + "）",
        actor.userId(),
        now);
    return detail(id).settlement();
  }

  // ================================================================ 作废

  /** 作废一张结算单（红冲的等价物）：终态，整单冻结。理由必填。 */
  @Transactional
  public FinanceSettlementRow voidSettlement(
      UUID id, FinanceSettlementVoidInput input, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_settlement where id = ?", id);
    if (head == null) throw notFound("结算单 " + id + " 不存在或无权操作");
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "作废必须写明理由");
    }
    String status = head.get("status", String.class);
    if (SettlementStatus.valueOf(status).terminal()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结算单已是终态（" + status + "），不可再作废");
    }
    OffsetDateTime now = nowUtc();
    sql.execute(
        "update finance_settlement set status='VOID', updated_at=?::timestamptz, version=version+1"
            + " where id = ?",
        now,
        id);
    writeEvent(actor.tenantId(), id, "VOIDED", status, "VOID", reason, actor.userId(), now);
    return detail(id).settlement();
  }

  // ================================================================ 结算周期配置

  /** 结算周期配置列表（一家店一份）。 */
  public List<FinanceSettlementCycleRow> cycles() {
    return sql.fetch("select * from finance_settlement_cycle order by shop_id")
        .map(this::cycleRow);
  }

  /**
   * 登记 / 改写一家店的结算周期配置（按店铺 upsert）。
   *
   * <p>⚠️ **预计到账时间**由它派生：结算周期结束日 + 到账滞后天数。
   */
  @Transactional
  public FinanceSettlementCycleRow upsertCycle(
      FinanceSettlementCycleInput input, AccessService.Access actor, String bearer) {
    if (input.shopId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定店铺");
    }
    if (input.cycleType() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定结算周期类型");
    }
    if (input.payoutLagDays() < 0 || input.payoutLagDays() > 365) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "到账滞后天数必须在 0 到 365 之间");
    }
    String currency =
        input.currency() == null || input.currency().isBlank()
            ? null
            : requireCurrency(input.currency());
    var shop = requireShop(input.shopId(), bearer);
    OffsetDateTime now = nowUtc();
    sql.execute(
        "insert into finance_settlement_cycle(id,tenant_id,shop_id,shop_code,platform_code,cycle_type,"
            + " payout_lag_days,currency,status,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id, shop_id) do update set shop_code=excluded.shop_code,"
            + " platform_code=excluded.platform_code, cycle_type=excluded.cycle_type,"
            + " payout_lag_days=excluded.payout_lag_days, currency=excluded.currency,"
            + " status=excluded.status, note=excluded.note, updated_at=excluded.updated_at",
        UUID.randomUUID(),
        actor.tenantId(),
        shop.id(),
        shop.code(),
        shop.platformCode(),
        input.cycleType().name(),
        input.payoutLagDays(),
        currency,
        input.status() == null || input.status().isBlank() ? "ACTIVE" : input.status(),
        input.note() == null ? "" : input.note(),
        now,
        now);
    var row =
        sql.fetchOne("select * from finance_settlement_cycle where shop_id = ?", shop.id());
    return cycleRow(row);
  }

  // ================================================================ 内部工具

  /** 该店铺的结算周期配置（可空）。 */
  private Record cycleOf(UUID shopId) {
    return sql.fetchOne("select * from finance_settlement_cycle where shop_id = ?", shopId);
  }

  /** 预计到账时间 = 结算周期结束日 + 到账滞后天数（该日 00:00:00Z）。 */
  private static OffsetDateTime expectedAt(LocalDate periodEnd, Record cycle) {
    if (cycle == null) return null;
    int lag = cycle.get("payout_lag_days", Integer.class);
    return periodEnd.plusDays(lag).atStartOfDay().atOffset(ZoneOffset.UTC);
  }

  /** 写明细行（一次多行插入 ⇒ 语句级触发器只算一次汇总）。导入路径。 */
  private void insertImportedLines(
      UUID tenantId, UUID settlementId, String currency, List<SettlementImportParser.ImportRow> rows) {
    if (rows.isEmpty()) return;
    var sqlText =
        new StringBuilder(
            "insert into finance_settlement_line(id,tenant_id,settlement_id,line_no,category,amount,"
                + " raw_amount,currency,quantity,occurred_on,external_reference,description,created_at)"
                + " values(?,?,?,?,?,?,?,?,?,?::date,?,?,?::timestamptz)");
    var binds = new ArrayList<Object>();
    OffsetDateTime now = nowUtc();
    int lineNo = 0;
    for (var row : rows) {
      lineNo++;
      if (lineNo > 1) sqlText.append(",(?,?,?,?,?,?,?,?,?,?::date,?,?,?::timestamptz)");
      binds.add(UUID.randomUUID());
      binds.add(tenantId);
      binds.add(settlementId);
      binds.add(lineNo);
      binds.add(row.category().name());
      binds.add(row.amount());
      binds.add(row.rawAmount());
      binds.add(row.currency() == null ? currency : row.currency());
      binds.add(row.quantity());
      binds.add(row.occurredOn());
      binds.add(row.externalReference());
      binds.add(row.description() == null ? "" : row.description());
      binds.add(now);
    }
    sql.execute(sqlText.toString(), binds.toArray());
  }

  /** 写明细行（手工登记用）。 */
  private void insertManualLines(
      UUID tenantId, UUID settlementId, String currency, List<FinanceSettlementLineInput> lines) {
    if (lines.isEmpty()) return;
    var sqlText =
        new StringBuilder(
            "insert into finance_settlement_line(id,tenant_id,settlement_id,line_no,category,amount,"
                + " raw_amount,currency,quantity,occurred_on,external_reference,description,created_at)"
                + " values(?,?,?,?,?,?,?,?,?,?::date,?,?,?::timestamptz)");
    var binds = new ArrayList<Object>();
    OffsetDateTime now = nowUtc();
    int lineNo = 0;
    for (var line : lines) {
      lineNo++;
      if (lineNo > 1) sqlText.append(",(?,?,?,?,?,?,?,?,?,?::date,?,?,?::timestamptz)");
      BigDecimal raw = money(line.amount());
      binds.add(UUID.randomUUID());
      binds.add(tenantId);
      binds.add(settlementId);
      binds.add(lineNo);
      binds.add(line.category().name());
      binds.add(line.category().normalize(raw));
      binds.add(raw);
      binds.add(currency);
      binds.add(line.quantity());
      binds.add(line.occurredOn());
      binds.add(blankToNull(line.externalReference()));
      binds.add(line.description() == null ? "" : line.description());
      binds.add(now);
    }
    sql.execute(sqlText.toString(), binds.toArray());
  }

  private void writeEvent(
      UUID tenantId,
      UUID settlementId,
      String type,
      String from,
      String to,
      String reason,
      UUID actorId,
      OffsetDateTime at) {
    sql.execute(
        "insert into finance_settlement_event(id,tenant_id,settlement_id,event_type,from_status,"
            + " to_status,reason,actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        settlementId,
        type,
        from,
        to,
        reason == null ? "" : reason,
        actorId,
        at);
  }

  private FinanceSettlementRow row(Record r) {
    return new FinanceSettlementRow(
        r.get("id", UUID.class),
        r.get("settlement_no", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_code", String.class),
        r.get("shop_name", String.class),
        r.get("platform_code", String.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("currency", String.class),
        r.get("settlement_amount", BigDecimal.class),
        SettlementStatus.valueOf(r.get("status", String.class)),
        r.get("expected_at", OffsetDateTime.class),
        r.get("arrived_at", OffsetDateTime.class),
        r.get("received_amount", BigDecimal.class),
        SettlementSource.valueOf(r.get("source", String.class)),
        r.get("source_system", String.class),
        r.get("source_reference", String.class),
        r.get("source_file_id", UUID.class),
        r.get("parse_version", Integer.class),
        r.get("cycle_id", UUID.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceSettlementSummaryRow summaryRow(Record r) {
    var category = SettlementCategory.valueOf(r.get("category", String.class));
    return new FinanceSettlementSummaryRow(
        r.get("id", UUID.class),
        r.get("settlement_id", UUID.class),
        category,
        category.label(),
        r.get("amount", BigDecimal.class),
        r.get("line_count", Long.class));
  }

  private FinanceSettlementLineRow lineRow(Record r) {
    var category = SettlementCategory.valueOf(r.get("category", String.class));
    return new FinanceSettlementLineRow(
        r.get("id", UUID.class),
        r.get("settlement_id", UUID.class),
        r.get("line_no", Integer.class),
        category,
        category.label(),
        r.get("amount", BigDecimal.class),
        r.get("raw_amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("quantity", BigDecimal.class),
        r.get("occurred_on", LocalDate.class),
        r.get("external_reference", String.class),
        r.get("description", String.class));
  }

  private FinanceSettlementCycleRow cycleRow(Record r) {
    var cycleType = SettlementCycleType.valueOf(r.get("cycle_type", String.class));
    return new FinanceSettlementCycleRow(
        r.get("id", UUID.class),
        r.get("shop_id", UUID.class),
        r.get("shop_code", String.class),
        r.get("platform_code", String.class),
        cycleType,
        cycleType.label(),
        r.get("payout_lag_days", Integer.class),
        r.get("currency", String.class),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceSettlementSourceFileRow sourceFileRow(Record r) {
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
    return new FinanceSettlementSourceFileRow(
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

  /** 店铺主数据必须从上游 channel 核验（跨服务只走授权 HTTP API）。 */
  private ChannelShopPort.ChannelShopView requireShop(UUID shopId, String bearer) {
    if (shopId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定店铺（结算单挂在一家具体的店铺上）");
    }
    for (var shop : shops.shops(bearer)) {
      if (shopId.equals(shop.id())) {
        return shop;
      }
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "店铺 " + shopId + " 在上游（2 渠道与店铺）不存在或不属于当前租户");
  }

  /** 明细行必须非空：没有明细行就没有金额，结算单也就没有意义。 */
  private List<FinanceSettlementLineInput> requireLines(List<FinanceSettlementLineInput> lines) {
    if (lines == null || lines.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "必须至少有一条结算明细行：结算金额由明细行求和得出，没有明细行就没有金额");
    }
    for (var line : lines) {
      if (line == null || line.category() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每一行明细都必须写明类别");
      }
      if (line.amount() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每一行明细都必须写明金额");
      }
      money(line.amount());
    }
    return lines;
  }

  private static String requireCurrency(String currency) {
    if (currency == null || !currency.matches("^[A-Za-z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三个字母（如 USD）");
    }
    return currency.trim().toUpperCase(java.util.Locale.ROOT);
  }

  private static String requireSourceSystem(String sourceSystem) {
    String value = blankToNull(sourceSystem);
    if (value == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "必须写明来源系统（如 amazon / shopify / ebay）：它与结算报告号组成幂等键");
    }
    if (value.length() > SOURCE_SYSTEM_MAX) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "来源系统最长 " + SOURCE_SYSTEM_MAX + " 个字符");
    }
    return value;
  }

  private static BigDecimal requireReceived(BigDecimal receivedAmount) {
    if (receivedAmount == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须写明实际到账金额");
    }
    BigDecimal value = money(receivedAmount);
    if (value.signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实际到账金额不能是负数");
    }
    return value;
  }

  /** 结算周期：结束日不能早于开始日。 */
  private static Period requirePeriod(LocalDate start, LocalDate end) {
    if (start == null || end == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "必须写明结算周期（开始日与结束日）：结算单是「一个周期一张单」");
    }
    if (end.isBefore(start)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结算周期的结束日不能早于开始日");
    }
    return new Period(start, end);
  }

  /** 结算周期区间。 */
  private record Period(LocalDate start, LocalDate end) {}

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String nextReference(String prefix) {
    return prefix
        + "-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  /**
   * 当前时刻（UTC，**截断到微秒**）。
   *
   * <p>⚠️ 截断不是洁癖：Postgres 的 timestamptz 精度就是微秒，
   * 不截断的话写进去的值和读回来的值会在最后一位上不同 ——
   * 「写时间的测试要断言写回的值」就会变成 flaky。
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
