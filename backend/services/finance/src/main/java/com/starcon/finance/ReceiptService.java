package com.starcon.finance;

import static com.starcon.finance.ReceiptModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 12.2.1.1 收款单。
 *
 * <p>蓝图 12-finance.md：「登记与跟踪资金收入：平台回款、银行到账、PayPal/Stripe/PingPong 等第三方支付到账、
 * 其他收入；记录资金账户、金额币种、到账时间与来源。」输入：「到账流水导入或手工登记；结算数据。」
 * 输出：「收款单 + 未核销余额。」
 *
 * <p><b>本模块的四条设计主线</b>：
 *
 * <ol>
 *   <li><b>上游没交付就说没交付，绝不造数。</b> 12.3.1.1 结算未交付 ⇒ 「平台回款」来源
 *       <b>当前不可产生</b>（服务层拒绝，错误消息写明归属模块）；12.7.1.1 账户未交付 ⇒
 *       受管资金账户引用 {@code fund_account_ref} <b>恒为 null</b>（DB CHECK 钉死）。
 *       填 0 或填一个假 id 都会被读成「这笔进了某个已登记的账户 / 来自某张结算单」，那是编事实。
 *   <li><b>到账时间不许静默偏 8 小时。</b> {@code received_at} 是 {@code timestamptz}，
 *       一律用 {@code OffsetDateTime} 绑定 + {@code ?::timestamptz}；
 *       导入时没有时区偏移的时间串<b>直接拒收</b>（{@code Timestamp.valueOf(LocalDateTime)}
 *       在东八区会偏 −28800s）。
 *   <li><b>预留 ≠ 核销。</b> 「一笔收款可核销多笔应收」（合并回款）由
 *       {@code finance_receipt_allocation} 表达，但它只是<b>登记</b>「打算核销到哪几笔应收」，
 *       <b>不减少</b>未核销余额。未核销余额只由 {@code settled_amount} 决定，
 *       而 {@code settled_amount} 的唯一写入口是 12.2.1.2 核销 —— <b>本模块不写它，也不开放写端点</b>。
 *   <li><b>币种分开。</b> 任何汇总都按币种分组；跨币种的预留核销在 DB 层被拒。
 * </ol>
 */
@Service
public class ReceiptService {

  private static final Logger logger = LoggerFactory.getLogger(ReceiptService.class);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 来源系统 / 来源流水号的长度上限（与 DB 列宽一致）。 */
  private static final int SOURCE_SYSTEM_MAX = 32;
  private static final int SOURCE_REFERENCE_MAX = 128;

  private final DSLContext sql;
  private final ObjectMapper json;

  public ReceiptService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 本模块的三个上游缺口必须分开说：
   * 「平台回款」的上游（12.3.1.1 结算）**未交付**；
   * 「资金账户」的上游（12.7.1.1 账户）**未交付**；
   * 「核销」（12.2.1.2）是**本服务自己的后续模块**、也未交付 ——
   * 把三者都写成「不可用」，运维会以为要去催结算，而实际上后两者是本服务内部的事。
   */
  public FinanceReceiptCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceReceiptCapability>();
    var gaps = new ArrayList<FinanceReceiptDownstreamGap>();

    capabilities.add(
        new FinanceReceiptCapability(
            "12.2.1.1",
            "收款单与未核销余额",
            true,
            "finance_receipt / finance_receipt_allocation / finance_receipt_event",
            "",
            "收款单与未核销余额已落地：数据落 PostgreSQL 16，查询走 jOOQ 显式 SQL，"
                + "全链路 BigDecimal + NUMERIC(19,4)，无浮点。"
                + "未核销余额是**生成列**（amount − settled_amount），没有第二个写入口。"));
    capabilities.add(
        new FinanceReceiptCapability(
            "12.2.1.1",
            "到账流水导入（CSV / JSON）",
            true,
            "POST /finance/receipts/import → 真解析 → 真入库（幂等键 = 来源系统 + 来源流水号）",
            "",
            "导入路径**真的能落地**：CSV（按 RFC 4180 处理引号）与 JSON 都能解析成收款单，"
                + "重复导入命中同一张单（幂等）。"
                + "文件级错误整体失败，行级错误只跳过那一行并逐条说明为什么 —— "
                + "「导入 0 条」如果不说清原因，现场会以为文件是空的。"));
    capabilities.add(
        new FinanceReceiptCapability(
            "12.2.1.1",
            "预留核销关系（合并回款）",
            true,
            "finance_receipt_allocation",
            "",
            "蓝图原话「一笔收款可核销多笔应收（合并回款）」由这张表承载："
                + "一笔收款可以预留到多笔应收上。"
                + "⚠️ **预留不是核销**：预留金额**不减少**未核销余额，"
                + "真正的核销金额由 12.2.1.2 写 settled_amount。"
                + "预收（还没有对应应收的资金）由 advance_flag 单列，"
                + "它与预留关系互斥（DB 层互斥守卫）。"));
    capabilities.add(
        new FinanceReceiptCapability(
            "12.3.1.1",
            "平台结算 → 收款（平台回款）",
            false,
            "上游 12.3.1.1 结算单管理未交付",
            "12.3.1.1",
            "结算单还不存在 ⇒ 「平台回款」来源**当前不可产生**，服务层拒绝建单并写明归属模块。"
                + "结算单号列已按「同一张表承接上游」的方式留好（并由 CHECK 钉死为 NULL），"
                + "12.3.1 交付后 DROP 那条 CHECK 即可接上，不需要改结构。"
                + "**本服务不填 0、也不造一条假结算。**"));
    capabilities.add(
        new FinanceReceiptCapability(
            "12.7.1.1",
            "资金账户（受管账户）",
            false,
            "上游 12.7.1.1 账户管理未交付",
            "12.7.1.1",
            "受管资金账户**当前不存在** ⇒ 收款单上的受管账户引用（fund_account_ref）"
                + "**恒为 null**（DB CHECK 钉死，填不出来）。"
                + "人能填的只有**自由文本**的账户标识（如 \"招商银行 6222****1234\"）—— "
                + "它**不是**受管资金账户，也没有与任何账户主数据关联，只是登记时的备注式标识。"
                + "12.7.1.1 交付后由它回填受管引用。"));
    capabilities.add(
        new FinanceReceiptCapability(
            "12.2.1.2",
            "核销（收款核销到应收）",
            false,
            "写路径未开放：核销金额的入口是 finance_receipt.settled_amount",
            "12.2.1.2",
            "本模块**只登记与跟踪**，不实现核销，也**不提供**任何「手工标记已核销」的端点。"
                + "核销金额由 12.2.1.2 写 settled_amount；"
                + "**核销未交付前，未核销余额恒等于收款金额**（还没有任何核销能减它）—— 这是口径，不是 bug。"
                + "预留核销关系（finance_receipt_allocation）只登记「打算核销到哪几笔应收」，不减少余额。"));
    capabilities.add(
        new FinanceReceiptCapability(
            "12.5.2.1",
            "凭证（过账后不可改行）",
            false,
            "本模块只保证「到账事实登记后不可改」这一半",
            "12.5.2.1",
            "收款单是凭证的**来源**而不是凭证本身；凭证的锁头 / 平衡 / 期间开放校验属总账。"));

    gaps.add(
        new FinanceReceiptDownstreamGap(
            "12.2.1.2",
            "核销",
            "finance",
            false,
            "12.2 收付款",
            "核销金额的入口（finance_receipt.settled_amount）留好了，写路径未开放；"
                + "未核销余额恒等于收款金额。"));
    gaps.add(
        new FinanceReceiptDownstreamGap(
            "12.7.1.1",
            "账户管理",
            "finance",
            false,
            "12.7 资金",
            "受管资金账户未交付 ⇒ 受管账户引用恒为 null；只有自由文本标识。"));
    gaps.add(
        new FinanceReceiptDownstreamGap(
            "12.3.1.1",
            "结算单管理",
            "finance",
            false,
            "12.3 结算与对账",
            "结算单未交付 ⇒ 「平台回款」来源当前不可产生。"));
    gaps.add(
        new FinanceReceiptDownstreamGap(
            "12.5.2.1",
            "凭证管理",
            "finance",
            false,
            "12.5 总账",
            "收款单是凭证来源；凭证本身的锁头 / 平衡 / 期间校验属总账。"));
    gaps.add(
        new FinanceReceiptDownstreamGap(
            "13.1.1.1",
            "利润与经营分析",
            "analytics",
            false,
            "13 利润与经营分析",
            "13 未交付：收款（回款口径）是它的一处上游输入。"));

    return new FinanceReceiptCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinanceReceiptOptions options() {
    var sources =
        sql.fetch("select distinct source from finance_receipt order by source")
            .map(r -> r.get(0, String.class));
    var sourceSystems =
        sql.fetch("select distinct source_system from finance_receipt order by source_system")
            .map(r -> r.get(0, String.class));
    var statuses =
        sql.fetch("select distinct status from finance_receipt order by status")
            .map(r -> r.get(0, String.class));
    var currencies =
        sql.fetch("select distinct currency from finance_receipt order by currency")
            .map(r -> r.get(0, String.class));
    var payerTypes =
        sql.fetch("select distinct payer_type from finance_receipt order by payer_type")
            .map(r -> r.get(0, String.class));
    return new FinanceReceiptOptions(sources, sourceSystems, statuses, currencies, payerTypes);
  }

  // ================================================================ 列表

  /**
   * 收款单列表：真分页 + 真筛选 + 真排序。
   *
   * <p>⚠️ **汇总覆盖的是整个筛选结果集，不是当前页**：分页一变汇总就变的话，
   * 「翻到第二页看到总额变小了」会被读成「数据丢了」。
   */
  public FinanceReceiptPage page(ReceiptQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from finance_receipt r" + clause, binds.toArray())
            .get(0, Long.class);
    List<FinanceReceiptRow> items =
        sql.fetch(
                "select * from finance_receipt r"
                    + clause
                    + " order by r.received_at desc, r.id desc limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::row);

    var summaries = summaryByCurrency(where, binds);
    return new FinanceReceiptPage(items, p, s, total, summaries);
  }

  /**
   * 按币种的汇总。
   *
   * <p>⚠️ **作废的单不计入**：一张已作废的收款在资金口径上不存在，
   * 把它算进「未核销余额」会让余额虚高。
   */
  private List<FinanceReceiptCurrencySummary> summaryByCurrency(
      List<String> where, List<Object> binds) {
    var clauses = new ArrayList<String>();
    clauses.add("r.status <> 'VOID'");
    clauses.addAll(where);
    return sql.fetch(
            "select r.currency,"
                + " sum(r.amount) as amount,"
                + " sum(r.settled_amount) as settled_amount,"
                + " sum(r.unsettled_amount) as unsettled_amount,"
                + " count(*) as cnt,"
                + " count(*) filter (where r.advance_flag) as advance_count,"
                + " coalesce(sum(r.unsettled_amount) filter (where r.advance_flag),0) as advance_amount"
                + " from finance_receipt r where "
                + String.join(" and ", clauses)
                + " group by r.currency order by r.currency",
            binds.toArray())
        .map(
            r ->
                new FinanceReceiptCurrencySummary(
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("settled_amount", BigDecimal.class),
                    r.get("unsettled_amount", BigDecimal.class),
                    r.get("cnt", Long.class),
                    r.get("advance_count", Long.class),
                    r.get("advance_amount", BigDecimal.class)));
  }

  private void applyFilters(ReceiptQuery q, List<String> where, List<Object> binds) {
    if (q.status() != null) {
      where.add("r.status = ?");
      binds.add(q.status().name());
    }
    if (q.source() != null) {
      where.add("r.source = ?");
      binds.add(q.source().name());
    }
    if (q.sourceSystem() != null && !q.sourceSystem().isBlank()) {
      where.add("r.source_system = ?");
      binds.add(q.sourceSystem().trim());
    }
    if (q.currency() != null && !q.currency().isBlank()) {
      where.add("r.currency = ?");
      binds.add(q.currency().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (q.payerType() != null) {
      where.add("r.payer_type = ?");
      binds.add(q.payerType().name());
    }
    if (q.advanceOnly() != null && q.advanceOnly()) {
      where.add("r.advance_flag");
    }
    if (q.receivedFrom() != null) {
      where.add("(r.received_at at time zone 'UTC')::date >= ?::date");
      binds.add(q.receivedFrom());
    }
    if (q.receivedTo() != null) {
      where.add("(r.received_at at time zone 'UTC')::date <= ?::date");
      binds.add(q.receivedTo());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add(
          "(r.receipt_no ilike ? or r.payer_name ilike ? or r.source_reference ilike ?"
              + " or r.fund_account_label ilike ? or r.note ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  // ================================================================ 详情

  /** 收款单详情：单头 + 预留核销关系 + 事件流。 */
  public FinanceReceiptDetail detail(UUID id) {
    var head = sql.fetchOne("select * from finance_receipt where id = ?", id);
    if (head == null) throw notFound("收款单 " + id + " 不存在或无权查看");
    var allocations =
        sql.fetch(
                "select a.*, rc.receipt_no, r.receivable_no from finance_receipt_allocation a"
                    + " join finance_receivable r on r.tenant_id = a.tenant_id and r.id = a.receivable_id"
                    + " join finance_receipt rc on rc.tenant_id = a.tenant_id and rc.id = a.receipt_id"
                    + " where a.receipt_id = ? order by a.created_at, a.id",
                id)
            .map(this::allocationRow);
    var events =
        sql.fetch(
                "select * from finance_receipt_event where receipt_id = ? order by occurred_at, id", id)
            .map(
                r ->
                    new FinanceReceiptEventRow(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("reason", String.class),
                        r.get("actor_id", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class)));
    return new FinanceReceiptDetail(row(head), allocations, events);
  }

  // ================================================================ 手工登记

  /**
   * 手工登记一张收款单。
   *
   * <p>⚠️ <b>「平台回款」当前不可产生</b>：上游 12.3.1.1 结算单管理未交付，
   * 服务层拒绝并写明归属模块 —— 不填 0、也不造一条假结算。
   *
   * <p>⚠️ <b>到账时间必填</b>：默认填当前时刻会把「昨天到账的钱」记成今天到账。
   */
  @Transactional
  public FinanceReceiptRow create(FinanceReceiptInput input, AccessService.Access actor) {
    ReceiptSource source = input.source() == null ? ReceiptSource.MANUAL : input.source();
    if (!source.producible()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "「"
              + source.label()
              + "」来源的上游是 12.3.1 结算，当前**未交付**，这笔收款无法产生。"
              + "请改用「银行到账」「第三方支付到账」「人工登记」或「其他收入」，"
              + "或等结算单落地后由结算数据自动产生");
    }
    String currency = requireCurrency(input.currency());
    BigDecimal amount = requireAmount(input.amount());
    OffsetDateTime receivedAt = requireReceivedAt(input.receivedAt());
    PayerType payerType = input.payerType() == null ? PayerType.CUSTOMER : input.payerType();
    String payerName = input.payerName() == null ? "" : input.payerName().trim();
    if (payerName.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须写明付款方名称");
    }
    String sourceSystem = blankToNull(input.sourceSystem());
    String sourceReference = truncate(blankToNull(input.sourceReference()), SOURCE_REFERENCE_MAX);
    if (sourceSystem == null) {
      if (sourceReference != null) {
        // 幂等键 = 来源系统 + 来源流水号：只写流水号不写来源系统，
        // 之后从真实来源系统导入同一笔流水时会**多出一张收款单**（同一笔钱记两次）。
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "写明了来源流水号就必须同时写明来源系统（如 bank / paypal / stripe / pingpong）："
                + "两者组成幂等键，只写流水号不写来源系统，同一笔流水会被登记两次");
      }
      sourceSystem = "manual";
    }
    String accountLabel = truncate(blankToNull(input.fundAccountLabel()), 120);
    if (accountLabel == null) accountLabel = "";
    boolean advance = input.advanceFlag() != null && input.advanceFlag();
    String note = input.note() == null ? "" : input.note();

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_receipt(id,tenant_id,receipt_no,source,source_system,source_reference,"
            + " currency,amount,settled_amount,advance_flag,status,received_at,payer_type,payer_name,"
            + " payer_ref,fund_account_ref,fund_account_label,settlement_no,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,0,?,'RECORDED',?::timestamptz,?,?,?,null,?,null,?,"
            + "?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        nextReference("RCPT"),
        source.name(),
        sourceSystem,
        sourceReference,
        currency,
        amount,
        advance,
        receivedAt,
        payerType.name(),
        payerName,
        input.payerRef(),
        accountLabel,
        note,
        now,
        now);
    writeEvent(
        actor.tenantId(), id, "CREATED", "", "RECORDED", "手工登记", actor.userId(), now);
    return detail(id).receipt();
  }

  // ================================================================ 到账流水导入

  /**
   * 到账流水导入（CSV / JSON）：解析 → 校验 → 真实入库 + 幂等。
   *
   * <p>⚠️ **幂等键 = 来源系统 + 来源流水号**：同一来源系统的同一笔流水重复导入只会命中同一条，
   * 不会长出第二张收款单。
   *
   * <p>⚠️ **行级错误只跳过那一行**，并逐条写进 warnings；文件级错误整体失败。
   */
  @Transactional
  public FinanceReceiptImportResult importFlow(
      FinanceReceiptImportInput input, AccessService.Access actor) {
    ReceiptSource source = input.source() == null ? ReceiptSource.BANK_TRANSFER : input.source();
    if (!source.producible()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "「"
              + source.label()
              + "」来源的上游是 12.3.1 结算，当前**未交付**，这批到账流水无法登记。"
              + "请改用「银行到账」「第三方支付到账」或「其他收入」");
    }
    if (input.format() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定导入格式（CSV 或 JSON）");
    }
    String sourceSystem = requireSourceSystem(input.sourceSystem());

    // ⚠️ **每次导入新建一个解析器**：解析器把行级警告攒在实例字段上，
    // 复用同一个实例会让**上一次导入的警告漏进这一次的结果**（看起来像这一批的行有问题）。
    var parsed = new ReceiptImportParser(json).parse(input.format(), input.content());
    var warnings = new ArrayList<>(parsed.warnings());
    var rows = new ArrayList<FinanceReceiptRow>();
    int imported = 0;
    int skipped = 0;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    for (var line : parsed.rows()) {
      var existing =
          sql.fetchOne(
              "select id from finance_receipt where source_system = ? and source_reference = ?",
              sourceSystem,
              line.sourceReference());
      if (existing != null) {
        // 幂等命中：这笔流水已经登记过了，不是错误。
        skipped++;
        continue;
      }
      UUID id = UUID.randomUUID();
      sql.execute(
          "insert into finance_receipt(id,tenant_id,receipt_no,source,source_system,source_reference,"
              + " currency,amount,settled_amount,advance_flag,status,received_at,payer_type,payer_name,"
              + " payer_ref,fund_account_ref,fund_account_label,settlement_no,note,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,0,false,'RECORDED',?::timestamptz,?,?,?,null,?,null,?,"
              + "?::timestamptz,?::timestamptz)",
          id,
          actor.tenantId(),
          nextReference("RCPT"),
          source.name(),
          sourceSystem,
          line.sourceReference(),
          line.currency(),
          line.amount(),
          line.receivedAt(),
          line.payerType().name(),
          line.payerName() == null ? "" : line.payerName(),
          null,
          line.fundAccountLabel() == null ? "" : line.fundAccountLabel(),
          line.note() == null ? "" : line.note(),
          now,
          now);
      writeEvent(
          actor.tenantId(),
          id,
          "IMPORTED",
          "",
          "RECORDED",
          "到账流水导入（来源流水号 " + line.sourceReference() + "）",
          actor.userId(),
          now);
      rows.add(row(sql.fetchOne("select * from finance_receipt where id = ?", id)));
      imported++;
    }

    // 解析阶段被跳过的行也要算进 skipped（scanned = imported + skipped 必须成立）。
    skipped += parsed.scanned() - parsed.rows().size();
    if (!warnings.isEmpty()) {
      // 警告不是秘密：它要出现在导入结果里，让人知道「有几条没进来」。
      logger.warn("[finance] 到账流水导入有 {} 条未进入：{}", warnings.size(), warnings);
    }
    return new FinanceReceiptImportResult(parsed.scanned(), imported, skipped, rows, warnings);
  }

  // ================================================================ 预留核销（合并回款）

  /**
   * 登记一笔「预留核销」：这笔钱**打算**核销到某一笔应收上。
   *
   * <p>⚠️ <b>预留不是核销</b>：它**不减少**未核销余额（余额只由 settled_amount 决定，
   * 而 settled_amount 的写入口是 12.2.1.2 核销）。这里登记的是「合并回款」的意图。
   *
   * <p>⚠️ <b>跨币种不允许</b>：USD 的钱不能预留到 EUR 的应收上（DB 层也有守卫）。
   */
  @Transactional
  public FinanceReceiptAllocationRow reserveAllocation(
      UUID receiptId, FinanceReceiptAllocationInput input, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_receipt where id = ?", receiptId);
    if (head == null) throw notFound("收款单 " + receiptId + " 不存在或无权操作");
    String status = head.get("status", String.class);
    if (ReceiptStatus.valueOf(status).terminal()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "收款单已是终态（" + status + "），不可再改");
    }
    if (head.get("advance_flag", Boolean.class)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "这笔收款是**预收**（还没有对应应收），不能有核销预留：预收 = 还没有对应应收的资金");
    }
    if (input.receivableId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定要预留的应收单");
    }
    BigDecimal reserved = requireAmount(input.reservedAmount());
    var receivable =
        sql.fetchOne("select * from finance_receivable where id = ?", input.receivableId());
    if (receivable == null) {
      throw notFound("应收单 " + input.receivableId() + " 不存在或无权查看");
    }
    String receiptCurrency = head.get("currency", String.class);
    String receivableCurrency = receivable.get("currency", String.class);
    if (!receiptCurrency.equals(receivableCurrency)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "跨币种不允许预留核销：收款单是 "
              + receiptCurrency
              + "，应收单是 "
              + receivableCurrency
              + "（币种分开是硬纪律，不同币种的金额加起来不是任何一个金额）");
    }
    BigDecimal receivableOpen =
        receivable
            .get("amount", BigDecimal.class)
            .subtract(receivable.get("settled_amount", BigDecimal.class));
    if (reserved.compareTo(receivableOpen) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "这笔应收的未核销余额只有 "
              + receivableOpen.toPlainString()
              + "，预留 "
              + reserved.toPlainString()
              + " 会超过它");
    }
    BigDecimal alreadyReserved =
        sql.fetchOne(
                "select coalesce(sum(reserved_amount),0) from finance_receipt_allocation"
                    + " where receipt_id = ? and status = 'RESERVED'",
                receiptId)
            .get(0, BigDecimal.class);
    BigDecimal headAmount = head.get("amount", BigDecimal.class);
    if (alreadyReserved.add(reserved).compareTo(headAmount) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "预留核销合计 "
              + alreadyReserved.add(reserved).toPlainString()
              + " 会超过收款单的金额 "
              + headAmount.toPlainString()
              + "（预留不是核销：未核销余额不会因预留而减少）");
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      sql.execute(
          "insert into finance_receipt_allocation(id,tenant_id,receipt_id,receivable_id,"
              + " reserved_amount,status,note,created_at,updated_at)"
              + " values(?,?,?,?,?,'RESERVED',?,?::timestamptz,?::timestamptz)",
          id,
          actor.tenantId(),
          receiptId,
          input.receivableId(),
          reserved,
          input.note() == null ? "" : input.note(),
          now,
          now);
    } catch (org.springframework.dao.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这笔收款对同一笔应收已经有预留关系了（重复预留是同一次预留）");
    }
    writeEvent(
        actor.tenantId(),
        receiptId,
        "RESERVED",
        status,
        status,
        "预留核销 " + reserved.toPlainString() + " 到应收单 " + input.receivableId(),
        actor.userId(),
        now);
    return allocationRow(
        sql.fetchOne(
            "select a.*, rc.receipt_no, r.receivable_no from finance_receipt_allocation a"
                + " join finance_receivable r on r.tenant_id = a.tenant_id and r.id = a.receivable_id"
                + " join finance_receipt rc on rc.tenant_id = a.tenant_id and rc.id = a.receipt_id"
                + " where a.id = ?",
            id));
  }

  /** 释放一笔预留核销（**不是**核销撤销 —— 核销撤销属 12.2.1.2）。 */
  @Transactional
  public FinanceReceiptAllocationRow releaseAllocation(
      UUID receiptId, UUID allocationId, AccessService.Access actor) {
    var allocation =
        sql.fetchOne(
            "select * from finance_receipt_allocation where id = ? and receipt_id = ?",
            allocationId,
            receiptId);
    if (allocation == null) throw notFound("预留核销关系 " + allocationId + " 不存在或无权操作");
    String status = allocation.get("status", String.class);
    if (!"RESERVED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "这条预留核销关系已经是「" + status + "」了，不可再释放");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_receipt_allocation set status='RELEASED', updated_at=?::timestamptz"
            + " where id = ?",
        now,
        allocationId);
    writeEvent(
        actor.tenantId(),
        receiptId,
        "RELEASED",
        "RECORDED",
        "RECORDED",
        "释放预留核销 " + allocation.get("reserved_amount", BigDecimal.class).toPlainString(),
        actor.userId(),
        now);
    return allocationRow(
        sql.fetchOne(
            "select a.*, rc.receipt_no, r.receivable_no from finance_receipt_allocation a"
                + " join finance_receivable r on r.tenant_id = a.tenant_id and r.id = a.receivable_id"
                + " join finance_receipt rc on rc.tenant_id = a.tenant_id and rc.id = a.receipt_id"
                + " where a.id = ?",
            allocationId));
  }

  // ================================================================ 作废

  /** 作废一张收款单（红冲的等价物）：终态，整单冻结。理由必填。 */
  @Transactional
  public FinanceReceiptRow voidReceipt(
      UUID id, FinanceReceiptVoidInput input, AccessService.Access actor) {
    var head = sql.fetchOne("select * from finance_receipt where id = ?", id);
    if (head == null) throw notFound("收款单 " + id + " 不存在或无权操作");
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    if (reason.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "作废必须写明理由");
    }
    String status = head.get("status", String.class);
    if (ReceiptStatus.valueOf(status).terminal()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收款单已是终态（" + status + "），不可再作废");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_receipt set status='VOID', updated_at=?::timestamptz, version=version+1"
            + " where id = ?",
        now,
        id);
    writeEvent(actor.tenantId(), id, "VOIDED", status, "VOID", reason, actor.userId(), now);
    return detail(id).receipt();
  }

  // ================================================================ 未核销余额

  /**
   * 未核销余额（按币种分开）。
   *
   * <p>⚠️ **核销未交付（12.2.1.2）⇒ 未核销余额恒等于收款金额**：
   * 还没有任何核销能减它。这是口径，不是缺陷 —— 能力声明里也这么写。
   *
   * <p>⚠️ **不跨币种相加**：USD 与 EUR 加起来不是任何一个金额。
   */
  public FinanceReceiptBalance balance() {
    var totals = summaryByCurrency(List.of(), List.of());
    long receiptCount =
        sql.fetchOne("select count(*) from finance_receipt where status <> 'VOID'")
            .get(0, Long.class);
    long advanceCount =
        sql.fetchOne(
                "select count(*) from finance_receipt where status <> 'VOID' and advance_flag")
            .get(0, Long.class);
    return new FinanceReceiptBalance(
        OffsetDateTime.now(ZoneOffset.UTC), totals, receiptCount, advanceCount);
  }

  // ================================================================ 工具

  private void writeEvent(
      UUID tenantId,
      UUID receiptId,
      String type,
      String from,
      String to,
      String reason,
      UUID actorId,
      OffsetDateTime at) {
    sql.execute(
        "insert into finance_receipt_event(id,tenant_id,receipt_id,event_type,from_status,"
            + " to_status,reason,actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        receiptId,
        type,
        from,
        to,
        reason == null ? "" : reason,
        actorId,
        at);
  }

  private FinanceReceiptRow row(Record r) {
    return new FinanceReceiptRow(
        r.get("id", UUID.class),
        r.get("receipt_no", String.class),
        ReceiptSource.valueOf(r.get("source", String.class)),
        r.get("source_system", String.class),
        r.get("source_reference", String.class),
        r.get("currency", String.class),
        r.get("amount", BigDecimal.class),
        r.get("settled_amount", BigDecimal.class),
        r.get("unsettled_amount", BigDecimal.class),
        r.get("advance_flag", Boolean.class),
        ReceiptStatus.valueOf(r.get("status", String.class)),
        r.get("received_at", OffsetDateTime.class),
        PayerType.valueOf(r.get("payer_type", String.class)),
        r.get("payer_name", String.class),
        r.get("payer_ref", UUID.class),
        r.get("fund_account_ref", UUID.class),
        r.get("fund_account_label", String.class),
        r.get("settlement_no", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceReceiptAllocationRow allocationRow(Record r) {
    return new FinanceReceiptAllocationRow(
        r.get("id", UUID.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("receivable_id", UUID.class),
        r.get("receivable_no", String.class),
        r.get("reserved_amount", BigDecimal.class),
        AllocationStatus.valueOf(r.get("status", String.class)),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static String nextReference(String prefix) {
    return prefix
        + "-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String truncate(String value, int max) {
    if (value == null) return null;
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static String requireCurrency(String currency) {
    if (currency == null || !currency.matches("^[A-Za-z]{3}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "币种必须是三个字母（如 USD）");
    }
    return currency.trim().toUpperCase(java.util.Locale.ROOT);
  }

  private static BigDecimal requireAmount(BigDecimal amount) {
    if (amount == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须写明金额");
    }
    BigDecimal value = money(amount);
    if (value.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额必须大于 0");
    }
    return value;
  }

  /**
   * 到账时间必填。
   *
   * <p>⚠️ 用 {@code Timestamp.valueOf(LocalDateTime)} 补默认值会静默偏 −28800s，
   * 所以这里**只接受带偏移的时刻**：要么给 {@code OffsetDateTime}，要么不给。
   */
  private static OffsetDateTime requireReceivedAt(OffsetDateTime receivedAt) {
    if (receivedAt == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "必须写明到账时间：默认填当前时刻会把「昨天到账的钱」记成今天到账（到账时间错 8 小时就是错账）");
    }
    return receivedAt;
  }

  private static String requireSourceSystem(String sourceSystem) {
    String value = blankToNull(sourceSystem);
    if (value == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "必须写明来源系统（如 bank / paypal / stripe / pingpong）：它与来源流水号组成幂等键");
    }
    if (value.length() > SOURCE_SYSTEM_MAX) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "来源系统最长 " + SOURCE_SYSTEM_MAX + " 个字符");
    }
    return value;
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
