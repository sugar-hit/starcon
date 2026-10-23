package com.starcon.finance;

import static com.starcon.finance.PaymentExecutionModels.*;

import com.starcon.finance.PaymentExecutionModels.PaymentAttemptKind;
import com.starcon.finance.PaymentExecutionModels.PaymentAttemptOutcome;
import com.starcon.finance.PaymentExecutionModels.PaymentExecutionStatus;
import com.starcon.finance.PaymentExecutionModels.PaymentFailureClass;
import com.starcon.finance.PaymentOrderModels.FinancePaymentExchangeRateView;
import com.starcon.finance.PaymentOrderModels.PaymentMethod;
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
 * 12.2.2.2 付款执行。
 *
 * <p>蓝图 12-finance.md：「<b>执行付款：对接支付渠道/银企直连，发送付款指令，跟踪结果，
 * 失败重试与异常处理；处理汇率锁汇。</b>输入：已批付款单；支付渠道配置（密钥引用）；汇率锁定。
 * 输出：<b>付款结果 + 渠道流水号 + 失败原因</b>。关系：上游 = 12.2.2.1；
 * 下游 = 12.7.1 资金账户、12.5.2 凭证。
 * <b>外部支付超时不等于失败</b>，必须先查证再重发——重复付款是严重资损事故。」
 *
 * <p><b>本模块的四条设计主线</b>：
 *
 * <ol>
 *   <li><b>三个阶段、三次事务。</b>渠道调用**绝不在数据库事务里**：
 *       <ol>
 *         <li>{@code prepare} —— 落执行单（**幂等键先入库**）、推进付款单到「执行中」、锁汇；</li>
 *         <li>{@code dispatch} —— 调渠道（<b>无事务</b>）；</li>
 *         <li>{@code settle} —— 落尝试记录、写结论、回写行级已付金额。</li>
 *       </ol>
 *       为什么要分段：渠道调用与事务绑在一起 ⇒ 渠道付了钱而事务回滚 ⇒
 *       本地**没有任何记录**说这笔钱付过。分段之后，最坏情况是「执行单停在已发送、
 *       没有结论」⇒ 下一步是<b>查证</b>而不是重发。
 *       ⚠️ 但真正的兜底是<b>确定性幂等键</b>：键由「付款单 + 分批」派生，
 *       即使本地记录全丢了，重发用的还是同一个键 ⇒ 渠道侧去重 ⇒ <b>不会重复付款</b>。
 *   <li><b>超时不等于失败。</b>渠道超时 / 5xx / 不可达 ⇒ 执行单进
 *       {@code UNKNOWN}，<b>不</b>进 {@code FAILED}；付款单停在「执行中」。
 *       重发前<b>必须先查证</b>：查证发现渠道侧已成功 ⇒ <b>不重发</b>，直接收敛成「已付」。
 *   <li><b>凭据只有引用。</b>渠道配置里存的是密钥管理器的引用 ID；
 *       解析不到凭据 ⇒ 明确报「渠道未配置」，<b>绝不用假凭据发请求，也绝不假装成功</b>。
 *   <li><b>行级已付金额是增量回写，且分摊留痕。</b>
 *       {@code finance_payable_line.paid_amount} 在<b>付款成功那一刻</b>加上去
 *       （V346 明确把这一列的写入所有权留给本模块）。
 *       12.2.2.1 允许<b>分批安排</b> ⇒ 每一批的钱落在哪几行应付明细上必须**当场记下来**
 *       （{@code finance_payment_execution_allocation}），
 *       否则第二次付款时无法判断「这一行已经收了多少」⇒ 就会重复付款。
 * </ol>
 *
 * <p><b>跨模块边界</b>：上游 12.2.2.1 的付款单与分批在本服务内 ⇒ 走 jOOQ 显式 SQL。
 * 本模块**只**推进它们的状态（执行中 / 已付 / 失败）并回写行级已付金额，
 * 其余字段一个都不改（触发器挡着）。
 */
@Service
public class PaymentExecutionService {

  private static final Logger logger = LoggerFactory.getLogger(PaymentExecutionService.class);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 汇率锁定的有效期（小时）。
   *
   * <p>锁汇窗口取 72 小时：跨境付款从发出到到账通常在 1–3 个工作日，
   * 窗口太短会让「锁还在、钱已经该付」对不上；太长则汇率漂移的风险回来。
   */
  static final int RATE_LOCK_HOURS = 72;

  private final DSLContext sql;
  private final PaymentChannelPort channels;
  private final ExchangeRatePort rates;

  public PaymentExecutionService(DSLContext sql, PaymentChannelPort channels, ExchangeRatePort rates) {
    this.sql = sql;
    this.channels = channels;
    this.rates = rates;
  }

  // ================================================================ 内部结构

  /**
   * 一次「准备好了的动作」：要发给谁、发什么、属于哪个阶段。
   *
   * <p>它跨三个阶段传递（准备 → 渠道调用 → 落结论），所以必须是**不可变**的。
   */
  public record PaymentExecutionPrepared(
      UUID executionId,
      UUID orderId,
      PaymentAttemptKind attemptKind,
      PaymentChannelPort.PaymentChannelConfig channel,
      PaymentChannelPort.PaymentInstruction instruction,
      String idempotencyKey,
      /** 本次尝试的编号（SEND 按发送次数、QUERY 按查证次数，各自从 1 起）。 */
      int attemptNo,
      BigDecimal amount,
      String currency) {}

  /** 一条应付明细行 + 它的当前已付事实。 */
  private record PayableLineState(
      UUID orderLineId, UUID payableLineId, BigDecimal orderAmount, BigDecimal payableAmount,
      BigDecimal payablePaid) {}

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>🔴 渠道侧的可用性是<b>运行时事实</b>（有没有凭据、主机在不在白名单），
   * 所以这里报的是「机制已接通」与「**当前有没有可用渠道**」两件事，不是一句「支持」。
   */
  public FinancePaymentExecutionCapabilities capabilities() {
    var capabilities = new ArrayList<FinancePaymentExecutionCapability>();
    var gaps = new ArrayList<FinancePaymentExecutionDownstreamGap>();

    capabilities.add(
        new FinancePaymentExecutionCapability(
            "12.2.2.2",
            "付款执行（发送付款指令）",
            true,
            "finance_payment_execution（+ attempt / event / allocation）；渠道走 PaymentChannelPort 抽象",
            "",
            "付款单从「已批」推进到「执行中 → 已付 / 失败」，全程走 jOOQ 显式 SQL，"
                + "金额一律 BigDecimal + NUMERIC，无浮点。"
                + "🔴 **渠道侧没有内置的假成功**：没有凭据 / 主机不在白名单 / 渠道未启用 ⇒ "
                + "明确报「渠道不可用 / 未配置」并如实记成失败，绝不假装成功。"));

    capabilities.add(
        new FinancePaymentExecutionCapability(
            "12.2.2.2",
            "幂等键 + 重发前查证",
            true,
            "finance_payment_execution.idempotency_key（UNIQUE(tenant_id, idempotency_key)）"
                + " + finance_payment_execution_attempt.attempt_kind",
            "",
            "发送**之前**先把幂等键落库（键由「付款单 + 分批」确定性派生 ⇒ 重发用同一个键）。"
                + "🔴 **外部支付超时不等于失败**：超时 / 5xx / 不可达 ⇒ 执行单进 UNKNOWN，"
                + "付款单停在「执行中」。重发**必须先查证**：查证发现渠道侧已成功 ⇒ **不重发**"
                + "（重复付款是严重资损事故）；查证仍查不出来 ⇒ 拒绝重发。"));

    capabilities.add(
        new FinancePaymentExecutionCapability(
            "12.2.2.2",
            "汇率锁汇",
            true,
            "finance_rate_lock（来源 = 12.2.2.1 建单时冻结的汇率快照）",
            "",
            "锁的是**单据冻结的汇率**，不是发送那一刻的市场价 —— "
                + "今天汇率变了不能让今天的付款悄悄按新汇率付出去。"
                + "付款指令里带上锁定的 rate / baseAmount，渠道回单金额对不上 ⇒ 判「金额不符」"
                + "并停在「执行中」等人工核对（**不**悄悄按渠道金额记账）。"
                + "单币种付款不锁汇（没有换算就没有锁，如实行为）。"));

    capabilities.add(
        new FinancePaymentExecutionCapability(
            "12.2.2.2",
            "行级已付金额回写",
            true,
            "finance_payment_execution_allocation + finance_payable_line.paid_amount",
            "",
            "「已付」这个事实在**付款成功那一刻**才成立 ⇒ 由本模块回写行级已付金额"
                + "（V340 的触发器本来就放行这一列）。"
                + "🔴 分批安排下每一批的钱落在哪几行上必须留痕（allocation 表），"
                + "否则第二次付款无法判断「这行已经收了多少」⇒ 就会重复付款。"));

    capabilities.add(
        new FinancePaymentExecutionCapability(
            "12.7.1.1",
            "资金账户（付款账户）",
            false,
            "本模块只读 12.2.2.1 登记的账户引用 + 名称 + 币种快照",
            "12.7.1.1",
            "12.7.1.1 资金账户（未交付）⇒ 本模块**不建账户表、不做账户余额**，"
                + "也不校验账户余额（没有余额真相可校验）。"
                + "它交付后由它接管账户与余额，本模块结构不用改。"));

    capabilities.add(
        new FinancePaymentExecutionCapability(
            "12.5.2.1",
            "凭证",
            false,
            "本模块不生成凭证",
            "12.5.2.1",
            "付款成功后**不生成凭证**（那会造出第二套账）。"
                + "本模块只留下「付款成功」这个事实与渠道流水号；凭证由 12.5.2.1 取数。"));

    gaps.add(
        new FinancePaymentExecutionDownstreamGap(
            "12.7.1.1",
            "资金账户",
            "finance",
            false,
            "下游未交付",
            "付款账户仍是 12.2.2.1 登记的「引用 + 名称 + 币种快照」，没有账户表也没有余额。"
                + "12.7.1.1 交付后由它接管账户登记与余额。"));

    gaps.add(
        new FinancePaymentExecutionDownstreamGap(
            "12.5.2.1",
            "凭证",
            "finance",
            false,
            "下游未交付",
            "付款成功只产生「付款事实 + 渠道流水号」，**不产生凭证**。"
                + "凭证由 12.5.2.1 从付款事实取数生成。"));

    return new FinancePaymentExecutionCapabilities(capabilities, gaps);
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinancePaymentExecutionOptions options() {
    var currencies =
        sql.fetch("select distinct currency from finance_payment_execution order by currency")
            .map(r -> r.get(0, String.class));
    var statuses =
        sql.fetch("select distinct status from finance_payment_execution order by status")
            .map(r -> r.get(0, String.class));
    var failureClasses =
        sql.fetch("select distinct failure_class from finance_payment_execution order by failure_class")
            .map(r -> r.get(0, String.class));
    var methods =
        sql.fetch(
                "select distinct payment_method from finance_payment_execution order by payment_method")
            .map(r -> r.get(0, String.class));
    var channelList =
        sql.fetch("select id, code, name from finance_payment_channel order by code")
            .map(
                r ->
                    new FinancePaymentExecutionOptionRef(
                        r.get("code", String.class),
                        r.get("name", String.class) + "（" + r.get("code", String.class) + "）"));
    var orderList =
        sql.fetch(
                "select distinct o.id, o.order_no from finance_payment_execution e"
                    + " join finance_payment_order o on o.tenant_id = e.tenant_id and o.id = e.order_id"
                    + " order by o.order_no")
            .map(
                r ->
                    new FinancePaymentExecutionOptionRef(
                        r.get("id", UUID.class).toString(), r.get("order_no", String.class)));
    return new FinancePaymentExecutionOptions(
        currencies, statuses, failureClasses, methods, channelList, orderList);
  }

  // ================================================================ 列表 / 详情

  /** 执行单列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
  public FinancePaymentExecutionPage page(PaymentExecutionQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from finance_payment_execution e" + clause, binds.toArray())
            .get(0, Long.class);
    List<FinancePaymentExecutionRow> items =
        sql.fetch(
                "select e.*, o.order_no, i.seq as installment_seq"
                    + " from finance_payment_execution e"
                    + " join finance_payment_order o on o.tenant_id = e.tenant_id and o.id = e.order_id"
                    + " left join finance_payment_order_installment i"
                    + "   on i.tenant_id = e.tenant_id and i.id = e.installment_id"
                    + clause
                    + " order by e.created_at desc, e.id desc limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::executionRow);

    var summaries = summaryByCurrency(where, binds);
    long unknown =
        summaries.stream().mapToLong(FinancePaymentExecutionCurrencySummary::unknownCount).sum();
    return new FinancePaymentExecutionPage(items, p, s, total, summaries, unknown);
  }

  private List<FinancePaymentExecutionCurrencySummary> summaryByCurrency(
      List<String> where, List<Object> binds) {
    var whereClause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    return sql.fetch(
            "select e.currency, sum(e.amount) as amount, count(*) as cnt,"
                + " count(*) filter (where e.status = 'SUCCEEDED') as succeeded_count,"
                + " count(*) filter (where e.status = 'FAILED') as failed_count,"
                + " count(*) filter (where e.status = 'UNKNOWN') as unknown_count"
                + " from finance_payment_execution e"
                + whereClause
                + " group by e.currency order by e.currency",
            binds.toArray())
        .map(
            r ->
                new FinancePaymentExecutionCurrencySummary(
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("cnt", Long.class),
                    r.get("succeeded_count", Long.class),
                    r.get("failed_count", Long.class),
                    r.get("unknown_count", Long.class)));
  }

  private void applyFilters(
      PaymentExecutionQuery q, List<String> where, List<Object> binds) {
    if (q.status() != null) {
      where.add("e.status = ?");
      binds.add(q.status().name());
    }
    if (q.failureClass() != null) {
      where.add("e.failure_class = ?");
      binds.add(q.failureClass().name());
    }
    if (q.currency() != null && !q.currency().isBlank()) {
      where.add("e.currency = ?");
      binds.add(q.currency());
    }
    if (q.channelCode() != null && !q.channelCode().isBlank()) {
      where.add("e.channel_code = ?");
      binds.add(q.channelCode());
    }
    if (q.orderId() != null) {
      where.add("e.order_id = ?");
      binds.add(q.orderId());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add("(e.execution_no ilike ? or e.channel_serial_no ilike ? or e.idempotency_key ilike ?)");
      var like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  /** 执行单详情：执行单 + 尝试记录 + 事件流 + 分摊 + 锁汇。 */
  public FinancePaymentExecutionDetail detail(UUID id) {
    var execution = executionRow(requireExecution(id));
    return new FinancePaymentExecutionDetail(
        execution, attempts(id), events(id), allocations(id), rateLocks(id));
  }

  /**
   * 可发起付款执行的付款单（**已批 / 执行中**）。
   *
   * <p>有分批安排的付款单**逐批出现**（每一批是一个独立的执行对象）；没有分批的整单出现一次。
   * 已经建过执行单的那一笔会带上执行单号与状态 ⇒ 页面据此告诉用户「这一笔已经有执行单了，
   * 不要重复发起」。
   */
  public List<FinancePaymentExecutionPendingOrder> pendingOrders() {
    return sql.fetch(
            "select o.id, o.order_no, o.counterparty_name, o.currency, o.amount, o.payment_method,"
                + " o.status, i.id as installment_id, i.seq as installment_seq, i.planned_paid_date,"
                + " (e.id is not null) as has_execution, e.execution_no, e.status as execution_status"
                + " from finance_payment_order o"
                + " left join finance_payment_order_installment i"
                + "   on i.tenant_id = o.tenant_id and i.order_id = o.id"
                + " left join finance_payment_execution e"
                + "   on e.tenant_id = o.tenant_id and e.order_id = o.id"
                + "  and (e.installment_id = i.id or (e.installment_id is null and i.id is null))"
                + " where o.status in ('APPROVED','EXECUTING')"
                + "   and (i.id is not null or not exists ("
                + "     select 1 from finance_payment_order_installment x"
                + "      where x.tenant_id = o.tenant_id and x.order_id = o.id))"
                + " order by o.created_at desc, o.id desc, i.seq")
        .map(
            r ->
                new FinancePaymentExecutionPendingOrder(
                    r.get("id", UUID.class),
                    r.get("order_no", String.class),
                    r.get("counterparty_name", String.class),
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("payment_method", String.class),
                    r.get("status", String.class),
                    r.get("installment_id", UUID.class),
                    r.get("installment_seq", Integer.class),
                    r.get("planned_paid_date", LocalDate.class),
                    r.get("has_execution", Boolean.class),
                    r.get("execution_no", String.class),
                    r.get("execution_status", String.class)));
  }

  // ================================================================ 渠道配置

  /** 渠道列表（真实数据）。 */
  public List<FinancePaymentChannelRow> channels() {
    return sql.fetch("select * from finance_payment_channel order by code")
        .map(this::channelRow);
  }

  /** 登记一个支付渠道（**只存密钥引用**）。 */
  @Transactional
  public FinancePaymentChannelRow registerChannel(
      FinancePaymentChannelInput input, AccessService.Access actor) {
    if (input == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    var code = requireText(input.code(), "渠道编码");
    var name = requireText(input.name(), "渠道名称");
    var method = requireMethod(input.paymentMethod());
    var baseUrl = requireText(input.baseUrl(), "渠道地址");
    var secretRef = requireText(input.secretRef(), "凭据引用");
    if (!secretRef.matches("^(secret://|kv://|vault://|external-secrets://).+")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "凭据只能是**引用**（secret:// / kv:// / vault:// / external-secrets:// 开头），"
              + "本系统不保存任何明文密钥");
    }
    if (!baseUrl.matches("^https://.+")
        && !baseUrl.matches("^http://(127\\.0\\.0\\.1|localhost|\\[::1\\])(:\\d+)?/.*")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "渠道地址必须用 https（回环地址例外，仅本地联调）");
    }
    var exists =
        sql.fetchOne("select 1 from finance_payment_channel where code = ?", code) != null;
    if (exists) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "渠道编码 " + code + " 已存在");
    }
    var id = UUID.randomUUID();
    var at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_payment_channel(id,tenant_id,code,name,payment_method,base_url,"
            + " secret_ref,enabled,timeout_seconds,max_attempts,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        code,
        name,
        method.name(),
        baseUrl,
        secretRef,
        input.enabled() == null ? Boolean.TRUE : input.enabled(),
        input.timeoutSeconds() == null ? 15 : input.timeoutSeconds(),
        input.maxAttempts() == null ? 3 : input.maxAttempts(),
        input.note() == null ? "" : input.note().trim(),
        at,
        at);
    return channelRow(sql.fetchOne("select * from finance_payment_channel where id = ?", id));
  }

  /** 改一个支付渠道的配置。 */
  @Transactional
  public FinancePaymentChannelRow updateChannel(
      UUID id, FinancePaymentChannelInput input, AccessService.Access actor) {
    var existing = requireChannelRow(id);
    var at = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_payment_channel set name=?, payment_method=?, base_url=?, secret_ref=?,"
            + " enabled=?, timeout_seconds=?, max_attempts=?, note=?, version=version+1,"
            + " updated_at=?::timestamptz where id=?",
        input.name() == null ? existing.get("name", String.class) : requireText(input.name(), "渠道名称"),
        input.paymentMethod() == null
            ? existing.get("payment_method", String.class)
            : requireMethod(input.paymentMethod()).name(),
        input.baseUrl() == null
            ? existing.get("base_url", String.class)
            : requireText(input.baseUrl(), "渠道地址"),
        input.secretRef() == null
            ? existing.get("secret_ref", String.class)
            : requireText(input.secretRef(), "凭据引用"),
        input.enabled() == null ? existing.get("enabled", Boolean.class) : input.enabled(),
        input.timeoutSeconds() == null
            ? existing.get("timeout_seconds", Integer.class)
            : input.timeoutSeconds(),
        input.maxAttempts() == null
            ? existing.get("max_attempts", Integer.class)
            : input.maxAttempts(),
        input.note() == null ? existing.get("note", String.class) : input.note().trim(),
        at,
        id);
    return channelRow(sql.fetchOne("select * from finance_payment_channel where id = ?", id));
  }

  // ================================================================ 阶段一：准备

  /**
   * 阶段一：**准备**一次付款执行（自己的事务）。
   *
   * <p>这个阶段做四件事：读事实 → 校验 → <b>把幂等键落库</b> → 推进付款单到「执行中」。
   * 渠道调用在**下一个阶段**（无事务）。
   */
  @Transactional
  public PaymentExecutionPrepared prepare(
      FinancePaymentExecutionInput input, AccessService.Access actor) {
    if (input == null || input.orderId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定要执行的付款单");
    }
    var order = requireOrder(input.orderId());
    var status = order.get("status", String.class);
    if (!"APPROVED".equals(status) && !"EXECUTING".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有已批 / 执行中的付款单可以发起付款执行（当前：" + status + "）");
    }

    var channelRow = requireChannelByCode(input.channelCode());
    if (!channelRow.get("enabled", Boolean.class)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "渠道 " + channelRow.get("code", String.class) + " 未启用");
    }
    var channelMethod = channelRow.get("payment_method", String.class);
    var orderMethod = order.get("payment_method", String.class);
    if (!channelMethod.equals(orderMethod)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "渠道 " + channelRow.get("code", String.class) + " 支持的付款方式是 " + channelMethod
              + "，与付款单的 " + orderMethod + " 不一致：不能用 A 渠道去付 B 方式的单");
    }

    // ---- 分批（12.2.2.1 的「分批安排」） ----
    UUID installmentId = input.installmentId();
    var installments =
        sql.fetch(
            "select id, seq, planned_paid_date, currency, amount, status"
                + " from finance_payment_order_installment where order_id = ? order by seq",
            input.orderId());
    BigDecimal amount;
    String currency;
    if (!installments.isEmpty()) {
      if (installmentId == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "这张付款单有分批安排（共 " + installments.size() + " 批），必须指定要付哪一批");
      }
      var wanted = installmentId;
      var chosen =
          installments.stream()
              .filter(i -> i.get("id", UUID.class).equals(wanted))
              .findFirst()
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.BAD_REQUEST, "该分批不属于这张付款单"));
      var installmentStatus = chosen.get("status", String.class);
      if (!"PLANNED".equals(installmentStatus) && !"EXECUTING".equals(installmentStatus)
          && !"FAILED".equals(installmentStatus)) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "该分批当前是「" + installmentStatus + "」，不可再发起付款执行");
      }
      installmentId = chosen.get("id", UUID.class);
      amount = chosen.get("amount", BigDecimal.class);
      currency = chosen.get("currency", String.class);
    } else {
      if (installmentId != null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "这张付款单没有分批安排");
      }
      amount = order.get("amount", BigDecimal.class);
      currency = order.get("currency", String.class);
    }

    // ---- 幂等键（确定性派生 ⇒ 重发用同一个键） ----
    var key = idempotencyKey(input.orderId(), installmentId);
    var existing =
        sql.fetchOne("select * from finance_payment_execution where idempotency_key = ?", key);
    if (existing != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这笔付款已经有执行单（"
              + existing.get("execution_no", String.class)
              + "，状态 "
              + existing.get("status", String.class)
              + "）：不要重复发起，请到执行单详情页做查证或重发");
    }

    // ---- 预检渠道（**不发任何请求**） ----
    var config = toConfig(channelRow);
    var reason = channels.unavailableReason(config);
    if (reason != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "渠道不可用：" + reason);
    }

    // ---- 落执行单（**幂等键先入库**） ----
    var executionId = UUID.randomUUID();
    var executionNo = nextReference("PE");
    var at = OffsetDateTime.now(ZoneOffset.UTC);
    var baseCurrency = order.get("base_currency", String.class);
    var exchangeRate = order.get("exchange_rate", BigDecimal.class);
    var baseAmount = order.get("base_amount", BigDecimal.class);
    sql.execute(
        "insert into finance_payment_execution(id,tenant_id,order_id,installment_id,execution_no,"
            + " channel_id,channel_code,channel_name,payment_method,currency,amount,base_currency,"
            + " exchange_rate,base_amount,idempotency_key,status,attempt_count,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',0,?,?::timestamptz,?::timestamptz)",
        executionId,
        actor.tenantId(),
        input.orderId(),
        installmentId,
        executionNo,
        channelRow.get("id", UUID.class),
        channelRow.get("code", String.class),
        channelRow.get("name", String.class),
        channelMethod,
        currency,
        amount,
        baseCurrency,
        exchangeRate,
        baseAmount,
        key,
        input.note() == null ? "" : input.note().trim(),
        at,
        at);
    appendEvent(executionId, "CREATED", "", "PENDING", "创建付款执行单", actor.userId(), at);
    // 立刻推进到「发送中」：进程在渠道调用途中挂掉时，执行单停在「发送中」而不是「待发送」，
    // 下一步就明确是「查证」而不是「再发一次」。
    sql.execute(
        "update finance_payment_execution set status='SENDING', sent_at=?::timestamptz,"
            + " updated_at=?::timestamptz where id=?",
        at,
        at,
        executionId);

    // ---- 推进付款单 / 分批 ----
    advanceOrderToExecuting(executionId, input.orderId(), installmentId, actor, at);
    ensureRateLock(executionId, input.orderId(), actor, at);

    return new PaymentExecutionPrepared(
        executionId,
        input.orderId(),
        PaymentAttemptKind.SEND,
        config,
        instruction(order, executionNo, key, currency, amount, baseCurrency, exchangeRate,
            baseAmount, input.note()),
        key,
        1,
        amount,
        currency);
  }

  /**
   * 阶段一：准备一次<b>状态查证</b>。
   *
   * <p>查证**不**新建执行单 —— 它落在已有的执行单上（查证是查「那笔」，不是「再付一笔」）。
   */
  @Transactional
  public PaymentExecutionPrepared prepareVerify(
      UUID executionId, AccessService.Access actor) {
    var execution = requireExecution(executionId);
    var status = execution.get("status", String.class);
    if ("SUCCEEDED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这笔付款已经成功（渠道流水号 " + execution.get("channel_serial_no", String.class)
              + "），不需要查证");
    }
    if (!"PENDING".equals(status) && !"SENDING".equals(status) && !"UNKNOWN".equals(status)
        && !"FAILED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "当前状态「" + status + "」没有可查证的对象");
    }
    var channelRow = requireChannelRow(execution.get("channel_id", UUID.class));
    var config = toConfig(channelRow);
    var reason = channels.unavailableReason(config);
    if (reason != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "渠道不可用：" + reason);
    }
    var attemptNo =
        sql.fetchOne(
                "select coalesce(max(attempt_no), 0) from finance_payment_execution_attempt"
                    + " where execution_id = ? and attempt_kind = 'QUERY'",
                executionId)
            .get(0, Integer.class)
            + 1;
    var order = requireOrder(execution.get("order_id", UUID.class));
    return new PaymentExecutionPrepared(
        executionId,
        execution.get("order_id", UUID.class),
        PaymentAttemptKind.QUERY,
        config,
        instruction(
            order,
            execution.get("execution_no", String.class),
            execution.get("idempotency_key", String.class),
            execution.get("currency", String.class),
            execution.get("amount", BigDecimal.class),
            execution.get("base_currency", String.class),
            execution.get("exchange_rate", BigDecimal.class),
            execution.get("base_amount", BigDecimal.class),
            execution.get("note", String.class)),
        execution.get("idempotency_key", String.class),
        attemptNo,
        execution.get("amount", BigDecimal.class),
        execution.get("currency", String.class));
  }

  /**
   * 阶段一：准备一次<b>重发</b>。
   *
   * <p>⚠️ <b>必须先查证</b>：DB 触发器要求「重发前存在一条 QUERY 尝试」，
   * 所以本方法只在 {@code prepareVerify} + 查证落库之后才可能被调用。
   */
  @Transactional
  public PaymentExecutionPrepared prepareResend(
      UUID executionId, AccessService.Access actor) {
    var execution = requireExecution(executionId);
    var status = execution.get("status", String.class);
    if ("SUCCEEDED".equals(status)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这笔付款已经成功，不允许重发");
    }
    if (!"PENDING".equals(status) && !"SENDING".equals(status) && !"UNKNOWN".equals(status)
        && !"FAILED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "当前状态「" + status + "」不允许重发");
    }
    var channelRow = requireChannelRow(execution.get("channel_id", UUID.class));
    var config = toConfig(channelRow);
    var reason = channels.unavailableReason(config);
    if (reason != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "渠道不可用：" + reason);
    }
    var maxAttempts = channelRow.get("max_attempts", Integer.class);
    var attemptCount = execution.get("attempt_count", Integer.class);
    if (attemptCount >= maxAttempts) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "已达最大尝试次数（" + maxAttempts + " 次）：重发不是无限的，请先核对渠道与金额");
    }
    // 查证记录必须已经存在（DB 触发器也会拦一道）。
    var queried =
        sql.fetchOne(
                "select 1 from finance_payment_execution_attempt"
                    + " where execution_id = ? and attempt_kind = 'QUERY'",
                executionId)
            != null;
    if (!queried) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "重发前必须先做状态查证：查证确认渠道侧确实没有成功才允许重发");
    }
    var order = requireOrder(execution.get("order_id", UUID.class));
    var at = OffsetDateTime.now(ZoneOffset.UTC);
    // 重发 ⇒ 执行单回到「发送中」，付款单 / 分批也回到「执行中」。
    sql.execute(
        "update finance_payment_execution set status='SENDING', version=version+1,"
            + " updated_at=?::timestamptz where id=?",
        at,
        executionId);
    advanceOrderToExecuting(
        executionId,
        execution.get("order_id", UUID.class),
        execution.get("installment_id", UUID.class),
        actor,
        at);
    appendEvent(executionId, "RETRY", status, "SENDING", "查证通过后重发", actor.userId(), at);
    return new PaymentExecutionPrepared(
        executionId,
        execution.get("order_id", UUID.class),
        PaymentAttemptKind.SEND,
        config,
        instruction(
            order,
            execution.get("execution_no", String.class),
            execution.get("idempotency_key", String.class),
            execution.get("currency", String.class),
            execution.get("amount", BigDecimal.class),
            execution.get("base_currency", String.class),
            execution.get("exchange_rate", BigDecimal.class),
            execution.get("base_amount", BigDecimal.class),
            execution.get("note", String.class)),
        execution.get("idempotency_key", String.class),
        attemptCount + 1,
        execution.get("amount", BigDecimal.class),
        execution.get("currency", String.class));
  }

  // ================================================================ 阶段二：调渠道

  /**
   * 阶段二：**调渠道**（**无数据库事务**）。
   *
   * <p>🔴 这是本模块唯一一处会离开本系统的调用，**刻意不放进事务**：
   * 渠道已经付了钱而本地事务回滚 ⇒ 本地没有任何记录说这笔钱付过。
   * 分成三段之后，最坏情况是「执行单停在已发送、没有结论」⇒ 下一步是**查证**。
   */
  public PaymentChannelPort.ChannelOutcome dispatch(PaymentExecutionPrepared prepared) {
    if (prepared.attemptKind() == PaymentAttemptKind.QUERY) {
      return channels.query(prepared.channel(), prepared.idempotencyKey());
    }
    return channels.send(prepared.channel(), prepared.instruction());
  }

  // ================================================================ 阶段三：落结论

  /**
   * 阶段三：把渠道回执**如实**落库（自己的事务）。
   *
   * <p>🔴 「如实」是这个方法的全部要求：渠道说成功就是成功，说失败就是失败，
   * 查不出来就是「结果未知」—— 三者不许混同。
   */
  @Transactional
  public FinancePaymentExecutionResult settle(
      PaymentExecutionPrepared prepared,
      PaymentChannelPort.ChannelOutcome outcome,
      AccessService.Access actor) {
    var at = OffsetDateTime.now(ZoneOffset.UTC);
    var execution = requireExecution(prepared.executionId());

    // ---- 金额比对：渠道说成功但金额对不上 ⇒ 不能当成功 ----
    var effective = outcome;
    if (effective.outcome() == PaymentAttemptOutcome.SUCCESS) {
      var mismatch = amountMismatch(prepared, effective);
      if (mismatch != null) {
        effective =
            new PaymentChannelPort.ChannelOutcome(
                PaymentAttemptOutcome.AMOUNT_MISMATCH,
                effective.channelSerialNo(),
                mismatch,
                effective.responseSummary(),
                effective.paidAmount(),
                effective.paidCurrency());
      }
    }

    insertAttempt(prepared, effective, at);
    // 发送类尝试**计入**重发次数（查证不计入）—— DB 层要求「成功 / 失败 ⇒ 已尝试过」。
    if (prepared.attemptKind() == PaymentAttemptKind.SEND) {
      sql.execute(
          "update finance_payment_execution set attempt_count=attempt_count+1,"
              + " updated_at=?::timestamptz where id=?",
          at,
          prepared.executionId());
    }
    appendEvent(
        prepared.executionId(),
        eventTypeOf(prepared.attemptKind(), effective.outcome()),
        execution.get("status", String.class),
        statusAfter(effective.outcome(), execution.get("status", String.class)),
        effective.failureReason(),
        actor.userId(),
        at);

    var message =
        switch (effective.outcome()) {
          case SUCCESS -> settleSuccess(prepared, effective, actor, at);
          case REJECTED, NOT_CONFIGURED -> settleFailure(prepared, effective, actor, at);
          case AMOUNT_MISMATCH -> settleMismatch(prepared, effective, actor, at);
          case TIMEOUT, CHANNEL_UNAVAILABLE -> settleUnknown(prepared, effective, actor, at);
          case NOT_FOUND -> settleNotFound(prepared, effective, actor, at);
        };

    var updated = executionRow(requireExecution(prepared.executionId()));
    var status = updated.status();
    boolean resent = prepared.attemptKind() == PaymentAttemptKind.SEND;
    boolean verified = prepared.attemptKind() == PaymentAttemptKind.QUERY;
    boolean retryAllowed =
        status == PaymentExecutionStatus.UNKNOWN || status == PaymentExecutionStatus.FAILED;
    return new FinancePaymentExecutionResult(updated, resent, verified, retryAllowed, message);
  }

  /** 渠道确认成功：写结论 + 回写行级已付金额 + 推进付款单。 */
  private String settleSuccess(
      PaymentExecutionPrepared prepared,
      PaymentChannelPort.ChannelOutcome outcome,
      AccessService.Access actor,
      OffsetDateTime at) {
    sql.execute(
        "update finance_payment_execution set status='SUCCEEDED', channel_serial_no=?,"
            + " failure_reason='', failure_class='NONE', sent_at=coalesce(sent_at,?::timestamptz),"
            + " responded_at=?::timestamptz, settled_at=?::timestamptz, version=version+1,"
            + " updated_at=?::timestamptz where id=?",
        outcome.channelSerialNo(),
        at,
        at,
        at,
        at,
        prepared.executionId());
    var conflict = writeBackPaidAmount(prepared, actor, at);
    releaseRateLock(prepared.orderId(), at);
    advanceOrderToPaid(prepared.orderId(), prepared.executionId(), actor, at);
    return conflict == null
        ? "渠道确认付款成功，渠道流水号 " + outcome.channelSerialNo()
        : "渠道确认付款成功，渠道流水号 " + outcome.channelSerialNo() + "；⚠️ " + conflict;
  }

  /** 渠道明确拒绝（确定性失败）：付款单可以在条件满足时转「失败」。 */
  private String settleFailure(
      PaymentExecutionPrepared prepared,
      PaymentChannelPort.ChannelOutcome outcome,
      AccessService.Access actor,
      OffsetDateTime at) {
    sql.execute(
        "update finance_payment_execution set status='FAILED', failure_reason=?, failure_class=?,"
            + " sent_at=coalesce(sent_at,?::timestamptz), responded_at=?::timestamptz,"
            + " settled_at=?::timestamptz, version=version+1, updated_at=?::timestamptz where id=?",
        outcome.failureReason(),
        failureClassOf(outcome.outcome()).name(),
        at,
        at,
        at,
        at,
        prepared.executionId());
    advanceOrderToFailed(prepared.orderId(), prepared.executionId(), actor, at);
    return "渠道明确失败：" + outcome.failureReason();
  }

  /**
   * 渠道回单金额与指令金额对不上。
   *
   * <p>🔴 <b>不判失败</b>：钱可能已经动了（金额不对而已），判失败会让付款单释放明细
   * ⇒ 别的付款单可能把这几行再付一次 ⇒ <b>重复付款</b>。
   * ⇒ 停在 UNKNOWN，等人工核对。
   */
  private String settleMismatch(
      PaymentExecutionPrepared prepared,
      PaymentChannelPort.ChannelOutcome outcome,
      AccessService.Access actor,
      OffsetDateTime at) {
    sql.execute(
        "update finance_payment_execution set status='UNKNOWN', failure_reason=?,"
            + " failure_class='AMOUNT_MISMATCH', channel_serial_no=?,"
            + " sent_at=coalesce(sent_at,?::timestamptz), responded_at=?::timestamptz,"
            + " version=version+1, updated_at=?::timestamptz where id=?",
        outcome.failureReason(),
        outcome.channelSerialNo(),
        at,
        at,
        at,
        prepared.executionId());
    return "⚠️ " + outcome.failureReason() + "：付款单停在「执行中」等人工核对，不会自动重发";
  }

  /** 结果未知（超时 / 不可达）：**绝不**判失败。 */
  private String settleUnknown(
      PaymentExecutionPrepared prepared,
      PaymentChannelPort.ChannelOutcome outcome,
      AccessService.Access actor,
      OffsetDateTime at) {
    sql.execute(
        "update finance_payment_execution set status='UNKNOWN', failure_reason=?, failure_class=?,"
            + " sent_at=coalesce(sent_at,?::timestamptz), responded_at=?::timestamptz,"
            + " version=version+1, updated_at=?::timestamptz where id=?",
        outcome.failureReason(),
        failureClassOf(outcome.outcome()).name(),
        at,
        at,
        at,
        prepared.executionId());
    return "⚠️ " + outcome.failureReason() + "：付款单停在「执行中」，请先查证再决定要不要重发";
  }

  /** 查证结果：渠道侧确实没有这笔 ⇒ 允许重发。 */
  private String settleNotFound(
      PaymentExecutionPrepared prepared,
      PaymentChannelPort.ChannelOutcome outcome,
      AccessService.Access actor,
      OffsetDateTime at) {
    sql.execute(
        "update finance_payment_execution set status='UNKNOWN', failure_reason=?,"
            + " failure_class='NONE', last_verified_at=?::timestamptz, version=version+1,"
            + " updated_at=?::timestamptz where id=?",
        outcome.failureReason(),
        at,
        at,
        prepared.executionId());
    return "查证结果：渠道侧没有这笔付款 ⇒ 可以重发（重发用**同一个幂等键**）";
  }

  // ================================================================ 状态推进

  /** 付款单 / 分批推进到「执行中」。 */
  private void advanceOrderToExecuting(
      UUID executionId, UUID orderId, UUID installmentId, AccessService.Access actor, OffsetDateTime at) {
    var order = requireOrder(orderId);
    var status = order.get("status", String.class);
    if ("APPROVED".equals(status)) {
      sql.execute(
          "update finance_payment_order set status='EXECUTING', version=version+1,"
              + " updated_at=?::timestamptz where id=? and status='APPROVED'",
          at,
          orderId);
    } else if ("FAILED".equals(status)) {
      // 失败后重发 ⇒ 回到执行中，并把明细重新锁回本付款单。
      sql.execute(
          "update finance_payment_order set status='EXECUTING', version=version+1,"
              + " updated_at=?::timestamptz where id=? and status='FAILED'",
          at,
          orderId);
      sql.execute(
          "update finance_payment_order_line set active=true, updated_at=?::timestamptz"
              + " where order_id=? and not active",
          at,
          orderId);
    }
    if (installmentId != null) {
      var installment =
          sql.fetchOne("select * from finance_payment_order_installment where id = ?", installmentId);
      if (installment != null) {
        var installmentStatus = installment.get("status", String.class);
        if ("PLANNED".equals(installmentStatus) || "FAILED".equals(installmentStatus)) {
          sql.execute(
              "update finance_payment_order_installment set status='EXECUTING',"
                  + " failure_reason='', version=version+1, updated_at=?::timestamptz"
                  + " where id=? and status in ('PLANNED','FAILED')",
              at,
              installmentId);
        }
      }
    }
  }

  /**
   * 付款单推进到「已付」。
   *
   * <p>条件：至少一条成功的执行单，且**没有任何分批**还停在「已排期 / 执行中」。
   */
  private void advanceOrderToPaid(
      UUID orderId, UUID executionId, AccessService.Access actor, OffsetDateTime at) {
    var order = requireOrder(orderId);
    if (!"EXECUTING".equals(order.get("status", String.class))) return;
    var pending =
        sql.fetchOne(
                "select count(*) from finance_payment_order_installment"
                    + " where order_id = ? and status in ('PLANNED','EXECUTING')",
                orderId)
            .get(0, Long.class);
    if (pending > 0) return;
    var succeeded =
        sql.fetchOne(
                "select count(*) from finance_payment_execution where order_id = ? and status = 'SUCCEEDED'",
                orderId)
            .get(0, Long.class);
    if (succeeded == 0) return;
    sql.execute(
        "update finance_payment_order set status='PAID', failure_reason='', version=version+1,"
            + " updated_at=?::timestamptz where id=? and status='EXECUTING'",
        at,
        orderId);
  }

  /**
   * 付款单推进到「失败」。
   *
   * <p>🔴 <b>条件很严</b>：必须有确定性失败的执行单，且**没有任何成功或结果未知的执行单** ——
   * 只要有一笔可能已经付出去了，就**不能**判失败（判失败会释放明细 ⇒ 可能重复付款）。
   */
  private void advanceOrderToFailed(
      UUID orderId, UUID executionId, AccessService.Access actor, OffsetDateTime at) {
    var order = requireOrder(orderId);
    if (!"EXECUTING".equals(order.get("status", String.class))) return;
    var succeeded =
        sql.fetchOne(
                "select count(*) from finance_payment_execution where order_id = ? and status = 'SUCCEEDED'",
                orderId)
            .get(0, Long.class);
    var unknown =
        sql.fetchOne(
                "select count(*) from finance_payment_execution where order_id = ? and status = 'UNKNOWN'",
                orderId)
            .get(0, Long.class);
    if (succeeded > 0 || unknown > 0) return;
    var pending =
        sql.fetchOne(
                "select count(*) from finance_payment_order_installment"
                    + " where order_id = ? and status in ('PLANNED','EXECUTING')",
                orderId)
            .get(0, Long.class);
    if (pending > 0) return;
    var failed =
        sql.fetchOne(
                "select count(*) from finance_payment_execution where order_id = ? and status = 'FAILED'",
                orderId)
            .get(0, Long.class);
    if (failed == 0) return;
    sql.execute(
        "update finance_payment_order set status='FAILED', failure_reason=?, version=version+1,"
            + " updated_at=?::timestamptz where id=? and status='EXECUTING'",
        "渠道明确拒绝了这笔付款（详见执行单失败原因）",
        at,
        orderId);
  }

  // ================================================================ 行级已付金额回写

  /**
   * 回写行级已付金额（{@code finance_payable_line.paid_amount}）。
   *
   * <p>🔴 <b>分摊必须留痕</b>：分批安排下，每一笔成功的付款要落到哪几行应付明细上，
   * 必须当场记进 {@code finance_payment_execution_allocation} ——
   * 否则下一次付款时无法判断「这一行已经收了多少」⇒ 就会重复付款。
   *
   * @return null = 正常；非 null = 一句需要人工处理的说明
   */
  private String writeBackPaidAmount(
      PaymentExecutionPrepared prepared, AccessService.Access actor, OffsetDateTime at) {
    var orderId = prepared.orderId();
    var remaining = prepared.amount();

    // 已分摊（此前**成功**的执行单）。
    var already = new LinkedHashMap<UUID, BigDecimal>();
    sql.fetch(
            "select a.order_line_id, sum(a.amount) as paid"
                + " from finance_payment_execution_allocation a"
                + " join finance_payment_execution e"
                + "   on e.tenant_id = a.tenant_id and e.id = a.execution_id"
                + " where e.order_id = ? and e.status = 'SUCCEEDED' group by a.order_line_id",
            orderId)
        .forEach(r -> already.put(r.get("order_line_id", UUID.class), r.get("paid", BigDecimal.class)));

    var lines = payableLineStates(orderId);
    var conflicts = new ArrayList<String>();
    for (var line : lines) {
      if (remaining.signum() <= 0) break;
      var done = already.getOrDefault(line.orderLineId(), BigDecimal.ZERO);
      var available = line.orderAmount().subtract(done);
      if (available.signum() <= 0) continue;
      var capacity = line.payableAmount().subtract(line.payablePaid());
      if (capacity.signum() <= 0) continue;
      var take = remaining.min(available).min(capacity);
      if (take.signum() <= 0) continue;
      sql.execute(
          "insert into finance_payment_execution_allocation(id,tenant_id,execution_id,payable_line_id,"
              + " order_line_id,amount,created_at) values(?,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          actor.tenantId(),
          prepared.executionId(),
          line.payableLineId(),
          line.orderLineId(),
          take,
          at);
      var affected =
          sql.execute(
              "update finance_payable_line set paid_amount = paid_amount + ?, updated_at=?::timestamptz"
                  + " where id = ? and paid_amount + ? <= amount",
              take,
              at,
              line.payableLineId(),
              take);
      if (affected != 1) {
        conflicts.add("应付明细行 " + line.payableLineId() + " 的已付金额未能回写");
        continue;
      }
      remaining = remaining.subtract(take);
    }
    if (remaining.signum() > 0) {
      conflicts.add("本次付款有 " + remaining + " 未能在应付明细行上分摊（应付明细余额已被占用）");
    }
    if (conflicts.isEmpty()) {
      appendEvent(
          prepared.executionId(),
          "WRITEBACK_APPLIED",
          "SUCCEEDED",
          "SUCCEEDED",
          "行级已付金额已回写",
          actor.userId(),
          at);
      return null;
    }
    var detail = String.join("；", conflicts);
    appendEvent(
        prepared.executionId(),
        "WRITEBACK_CONFLICT",
        "SUCCEEDED",
        "SUCCEEDED",
        detail,
        actor.userId(),
        at);
    return detail;
  }

  /** 付款单的明细行 + 对应应付明细行的当前已付事实。 */
  private List<PayableLineState> payableLineStates(UUID orderId) {
    return sql.fetch(
            "select l.id as order_line_id, l.payable_line_id, l.amount as order_amount,"
                + " pl.amount as payable_amount, pl.paid_amount as payable_paid"
                + " from finance_payment_order_line l"
                + " join finance_payable_line pl on pl.tenant_id = l.tenant_id and pl.id = l.payable_line_id"
                + " where l.order_id = ? order by l.line_no",
            orderId)
        .map(
            r ->
                new PayableLineState(
                    r.get("order_line_id", UUID.class),
                    r.get("payable_line_id", UUID.class),
                    r.get("order_amount", BigDecimal.class),
                    r.get("payable_amount", BigDecimal.class),
                    r.get("payable_paid", BigDecimal.class)));
  }

  // ================================================================ 汇率锁汇

  /** 锁汇：单据冻结的汇率（**不是**发送那一刻的市场价）。 */
  private void ensureRateLock(
      UUID executionId, UUID orderId, AccessService.Access actor, OffsetDateTime at) {
    var order = requireOrder(orderId);
    var baseCurrency = order.get("base_currency", String.class);
    var rate = order.get("exchange_rate", BigDecimal.class);
    if (baseCurrency == null || rate == null) {
      // 单币种付款不锁汇（没有换算就没有锁）—— 如实行为，不是缺功能。
      return;
    }
    var existing =
        sql.fetchOne(
            "select * from finance_rate_lock where order_id = ? and status = 'LOCKED'", orderId);
    if (existing != null) {
      sql.execute(
          "update finance_rate_lock set execution_id=?, updated_at=?::timestamptz where id=?",
          executionId,
          at,
          existing.get("id", UUID.class));
      return;
    }
    sql.execute(
        "insert into finance_rate_lock(id,tenant_id,order_id,execution_id,quote_currency,base_currency,"
            + " rate,base_amount,source_code,source_name,locked_at,expires_at,status,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,'ORDER_SNAPSHOT','付款单冻结的汇率快照',?::timestamptz,?::timestamptz,"
            + " 'LOCKED',?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        actor.tenantId(),
        orderId,
        executionId,
        order.get("currency", String.class),
        baseCurrency,
        rate,
        order.get("base_amount", BigDecimal.class),
        at,
        at.plusHours(RATE_LOCK_HOURS),
        at,
        at);
    appendEvent(executionId, "RATE_LOCKED", "", "", "锁定汇率 " + rate + " " + baseCurrency,
        actor.userId(), at);
  }

  /** 付款成功 ⇒ 释放锁汇。 */
  private void releaseRateLock(UUID orderId, OffsetDateTime at) {
    sql.execute(
        "update finance_rate_lock set status='RELEASED', released_at=?::timestamptz,"
            + " updated_at=?::timestamptz where order_id=? and status='LOCKED'",
        at,
        at,
        orderId);
  }

  /** 当前市场汇率（**只读** catalog 5.2.2.1），用来对照单据锁定的汇率。 */
  public FinancePaymentExchangeRateView marketRate(
      String baseCurrency, String quoteCurrency, String bearer) {
    var from = requireCurrency(baseCurrency, "基准币种");
    var to = requireCurrency(quoteCurrency, "报价币种");
    var snapshot = rates.latest(from, to, LocalDate.now(ZoneOffset.UTC), bearer);
    return new FinancePaymentExchangeRateView(
        snapshot.baseCurrency(),
        snapshot.quoteCurrency(),
        snapshot.rate(),
        snapshot.effectiveDate(),
        snapshot.sourceCode(),
        snapshot.sourceName(),
        snapshot.inverted(),
        snapshot.display());
  }

  // ================================================================ 取数 / 映射

  /**
   * 读一条执行单（**带上付款单号与分批序号**）。
   *
   * <p>⚠️ 必须用与列表查询**同一套列**：{@link #executionRow(Record)} 会读
   * {@code order_no} / {@code installment_seq}，少 join 一次就会取不到。
   */
  private Record requireExecution(UUID id) {
    var row =
        sql.fetchOne(
            "select e.*, o.order_no, i.seq as installment_seq"
                + " from finance_payment_execution e"
                + " join finance_payment_order o on o.tenant_id = e.tenant_id and o.id = e.order_id"
                + " left join finance_payment_order_installment i"
                + "   on i.tenant_id = e.tenant_id and i.id = e.installment_id"
                + " where e.id = ?",
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "付款执行单不存在");
    }
    return row;
  }

  private Record requireOrder(UUID id) {
    var row = sql.fetchOne("select * from finance_payment_order where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "付款单不存在");
    }
    return row;
  }

  private Record requireChannelByCode(String code) {
    if (code == null || code.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须选择支付渠道");
    }
    var row =
        sql.fetchOne("select * from finance_payment_channel where code = ?", code.trim());
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "支付渠道不存在：" + code);
    }
    return row;
  }

  private Record requireChannelRow(UUID id) {
    var row = sql.fetchOne("select * from finance_payment_channel where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "支付渠道不存在");
    }
    return row;
  }

  /** 时间戳统一返回 UTC（与 {@code ExchangeRatePort.toOffset} 同一手法）。 */
  private static OffsetDateTime utc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }

  private FinancePaymentExecutionRow executionRow(Record r) {
    var installmentSeq = r.get("installment_seq", Integer.class);
    return new FinancePaymentExecutionRow(
        r.get("id", UUID.class),
        r.get("execution_no", String.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("installment_id", UUID.class),
        installmentSeq,
        r.get("channel_id", UUID.class),
        r.get("channel_code", String.class),
        r.get("channel_name", String.class),
        r.get("payment_method", String.class),
        r.get("currency", String.class),
        r.get("amount", BigDecimal.class),
        r.get("base_currency", String.class),
        r.get("exchange_rate", BigDecimal.class),
        r.get("base_amount", BigDecimal.class),
        r.get("idempotency_key", String.class),
        PaymentExecutionStatus.valueOf(r.get("status", String.class)),
        r.get("attempt_count", Integer.class),
        r.get("channel_serial_no", String.class),
        r.get("failure_reason", String.class),
        PaymentFailureClass.valueOf(r.get("failure_class", String.class)),
        utc(r.get("sent_at", OffsetDateTime.class)),
        utc(r.get("responded_at", OffsetDateTime.class)),
        utc(r.get("settled_at", OffsetDateTime.class)),
        utc(r.get("last_verified_at", OffsetDateTime.class)),
        r.get("note", String.class),
        utc(r.get("created_at", OffsetDateTime.class)),
        utc(r.get("updated_at", OffsetDateTime.class)));
  }

  private FinancePaymentChannelRow channelRow(Record r) {
    return new FinancePaymentChannelRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("payment_method", String.class),
        r.get("base_url", String.class),
        r.get("secret_ref", String.class),
        r.get("enabled", Boolean.class),
        r.get("timeout_seconds", Integer.class),
        r.get("max_attempts", Integer.class),
        r.get("note", String.class),
        utc(r.get("created_at", OffsetDateTime.class)),
        utc(r.get("updated_at", OffsetDateTime.class)));
  }

  private List<FinancePaymentExecutionAttemptRow> attempts(UUID executionId) {
    return sql.fetch(
            "select * from finance_payment_execution_attempt where execution_id = ?"
                + " order by attempt_kind desc, attempt_no",
            executionId)
        .map(
            r ->
                new FinancePaymentExecutionAttemptRow(
                    r.get("id", UUID.class),
                    r.get("attempt_no", Integer.class),
                    PaymentAttemptKind.valueOf(r.get("attempt_kind", String.class)),
                    r.get("idempotency_key", String.class),
                    PaymentAttemptOutcome.valueOf(r.get("outcome", String.class)),
                    r.get("channel_serial_no", String.class),
                    r.get("failure_reason", String.class),
                    r.get("request_summary", String.class),
                    r.get("response_summary", String.class),
                    r.get("started_at", OffsetDateTime.class),
                    r.get("finished_at", OffsetDateTime.class)));
  }

  private List<FinancePaymentExecutionEventRow> events(UUID executionId) {
    return sql.fetch(
            "select * from finance_payment_execution_event where execution_id = ?"
                + " order by occurred_at, id",
            executionId)
        .map(
            r ->
                new FinancePaymentExecutionEventRow(
                    r.get("id", UUID.class),
                    r.get("event_type", String.class),
                    r.get("from_status", String.class),
                    r.get("to_status", String.class),
                    r.get("reason", String.class),
                    r.get("actor_id", UUID.class),
                    r.get("occurred_at", OffsetDateTime.class)));
  }

  private List<FinancePaymentExecutionAllocationRow> allocations(UUID executionId) {
    return sql.fetch(
            "select * from finance_payment_execution_allocation where execution_id = ? order by created_at, id",
            executionId)
        .map(
            r ->
                new FinancePaymentExecutionAllocationRow(
                    r.get("id", UUID.class),
                    r.get("payable_line_id", UUID.class),
                    r.get("order_line_id", UUID.class),
                    r.get("amount", BigDecimal.class),
                    utc(r.get("created_at", OffsetDateTime.class))));
  }

  private List<FinanceRateLockRow> rateLocks(UUID executionId) {
    return sql.fetch(
            "select * from finance_rate_lock where execution_id = ? order by locked_at, id",
            executionId)
        .map(
            r ->
                new FinanceRateLockRow(
                    r.get("id", UUID.class),
                    r.get("order_id", UUID.class),
                    r.get("execution_id", UUID.class),
                    r.get("quote_currency", String.class),
                    r.get("base_currency", String.class),
                    r.get("rate", BigDecimal.class),
                    r.get("base_amount", BigDecimal.class),
                    r.get("source_code", String.class),
                    r.get("source_name", String.class),
                    r.get("effective_date", LocalDate.class),
                    r.get("locked_at", OffsetDateTime.class),
                    r.get("expires_at", OffsetDateTime.class),
                    RateLockStatus.valueOf(r.get("status", String.class)),
                    r.get("released_at", OffsetDateTime.class)));
  }

  // ================================================================ 写入辅助

  /** 落一条尝试记录（append-only）。 */
  private void insertAttempt(
      PaymentExecutionPrepared prepared,
      PaymentChannelPort.ChannelOutcome outcome,
      OffsetDateTime at) {
    sql.execute(
        "insert into finance_payment_execution_attempt(id,tenant_id,execution_id,attempt_no,"
            + " attempt_kind,idempotency_key,outcome,channel_serial_no,failure_reason,"
            + " request_summary,response_summary,started_at,finished_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenantId(),
        prepared.executionId(),
        prepared.attemptNo(),
        prepared.attemptKind().name(),
        prepared.idempotencyKey(),
        outcome.outcome().name(),
        outcome.channelSerialNo() == null ? "" : outcome.channelSerialNo(),
        outcome.failureReason() == null ? "" : outcome.failureReason(),
        requestSummary(prepared),
        outcome.responseSummary() == null ? "" : outcome.responseSummary(),
        at,
        at);
  }

  /** 请求摘要（**绝不含凭据**）。 */
  private String requestSummary(PaymentExecutionPrepared prepared) {
    var instruction = prepared.instruction();
    var summary =
        "ref="
            + instruction.reference()
            + " amount="
            + instruction.amount().toPlainString()
            + " "
            + instruction.currency()
            + (instruction.baseCurrency() == null
                ? ""
                : " base=" + instruction.baseAmount().toPlainString() + " " + instruction.baseCurrency()
                    + " @ " + instruction.exchangeRate().toPlainString())
            + " beneficiary="
            + instruction.beneficiaryType()
            + "/"
            + instruction.beneficiaryName()
            + " key="
            + instruction.idempotencyKey();
    return summary.length() > 900 ? summary.substring(0, 900) : summary;
  }

  /** 追加一条事件（append-only）。 */
  private void appendEvent(
      UUID executionId,
      String type,
      String from,
      String to,
      String reason,
      UUID actorId,
      OffsetDateTime at) {
    sql.execute(
        "insert into finance_payment_execution_event(id,tenant_id,execution_id,event_type,from_status,"
            + " to_status,reason,actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenantId(),
        executionId,
        type,
        from == null ? "" : from,
        to == null ? "" : to,
        reason == null ? "" : reason,
        actorId,
        at);
  }

  /** 当前事务里的租户 id（来自 RLS 上下文，**不是**客户端自称的）。 */
  private UUID currentTenantId() {
    var row = sql.fetchOne("select current_setting('app.current_tenant', true)::uuid as t");
    return row == null ? null : row.get("t", UUID.class);
  }

  // ================================================================ 工具

  /** 幂等键：**确定性**派生 ⇒ 同一笔逻辑付款的每次重发都用同一个键。 */
  static String idempotencyKey(UUID orderId, UUID installmentId) {
    return "pay-" + orderId + "-" + (installmentId == null ? "full" : installmentId);
  }

  private PaymentChannelPort.PaymentInstruction instruction(
      Record order,
      String reference,
      String key,
      String currency,
      BigDecimal amount,
      String baseCurrency,
      BigDecimal exchangeRate,
      BigDecimal baseAmount,
      String note) {
    var beneficiaryType = order.get("counterparty_type", String.class);
    var beneficiaryName = order.get("counterparty_name", String.class);
    var beneficiaryRef = order.get("counterparty_ref", UUID.class);
    return new PaymentChannelPort.PaymentInstruction(
        key,
        reference,
        currency,
        amount,
        baseCurrency,
        exchangeRate,
        baseAmount,
        beneficiaryType,
        beneficiaryName,
        beneficiaryRef == null ? null : beneficiaryRef.toString(),
        note);
  }

  private PaymentChannelPort.PaymentChannelConfig toConfig(Record channelRow) {
    return new PaymentChannelPort.PaymentChannelConfig(
        channelRow.get("code", String.class),
        channelRow.get("name", String.class),
        channelRow.get("payment_method", String.class),
        channelRow.get("base_url", String.class),
        channelRow.get("secret_ref", String.class),
        channelRow.get("timeout_seconds", Integer.class),
        channelRow.get("max_attempts", Integer.class));
  }

  /** 渠道回单的金额 / 币种与指令对不上 ⇒ 一句能直接展示的原因。 */
  private String amountMismatch(
      PaymentExecutionPrepared prepared, PaymentChannelPort.ChannelOutcome outcome) {
    var instruction = prepared.instruction();
    if (outcome.paidAmount() != null
        && outcome.paidAmount().compareTo(instruction.amount()) != 0) {
      return "渠道回单金额 " + outcome.paidAmount().toPlainString() + " " + outcome.paidCurrency()
          + " 与指令金额 " + instruction.amount().toPlainString() + " " + instruction.currency()
          + " 不一致";
    }
    if (outcome.paidCurrency() != null
        && !outcome.paidCurrency().equalsIgnoreCase(instruction.currency())) {
      return "渠道回单币种 " + outcome.paidCurrency() + " 与指令币种 " + instruction.currency()
          + " 不一致";
    }
    return null;
  }

  private PaymentFailureClass failureClassOf(PaymentAttemptOutcome outcome) {
    return switch (outcome) {
      case REJECTED -> PaymentFailureClass.CHANNEL_REJECTED;
      case NOT_CONFIGURED -> PaymentFailureClass.NOT_CONFIGURED;
      case TIMEOUT -> PaymentFailureClass.TIMEOUT;
      case CHANNEL_UNAVAILABLE -> PaymentFailureClass.CHANNEL_UNAVAILABLE;
      case AMOUNT_MISMATCH -> PaymentFailureClass.AMOUNT_MISMATCH;
      case NOT_FOUND -> PaymentFailureClass.NONE;
      case SUCCESS -> PaymentFailureClass.NONE;
    };
  }

  private String eventTypeOf(PaymentAttemptKind kind, PaymentAttemptOutcome outcome) {
    if (kind == PaymentAttemptKind.QUERY) {
      return switch (outcome) {
        case SUCCESS -> "VERIFY_SUCCEEDED";
        case NOT_FOUND -> "VERIFY_NOT_FOUND";
        default -> "VERIFY_UNKNOWN";
      };
    }
    return switch (outcome) {
      case SUCCESS -> "SEND_SUCCEEDED";
      case REJECTED, NOT_CONFIGURED, AMOUNT_MISMATCH -> "SEND_REJECTED";
      default -> "SEND_UNKNOWN";
    };
  }

  private String statusAfter(PaymentAttemptOutcome outcome, String current) {
    return switch (outcome) {
      case SUCCESS -> "SUCCEEDED";
      case REJECTED, NOT_CONFIGURED -> "FAILED";
      case AMOUNT_MISMATCH, TIMEOUT, CHANNEL_UNAVAILABLE -> "UNKNOWN";
      case NOT_FOUND -> current;
    };
  }

  /** 单号：前缀 + 12 位十六进制（大写）。 */
  private static String nextReference(String prefix) {
    return prefix
        + "-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
    }
    return value.trim();
  }

  private static String requireCurrency(String raw, String label) {
    var value = requireText(raw, label).toUpperCase(java.util.Locale.ROOT);
    if (!value.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "必须是三位大写币种代码（当前：" + raw + "）");
    }
    return value;
  }

  private PaymentMethod requireMethod(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "付款方式必须填写");
    }
    try {
      return PaymentMethod.valueOf(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "付款方式取值不合法：" + raw);
    }
  }

  /** 拼参数（注意顺序：where 条件在前，limit/offset 在后）。 */
  private static Object[] concat(List<Object> binds, Object... tail) {
    var all = new ArrayList<>(binds);
    all.addAll(java.util.Arrays.asList(tail));
    return all.toArray();
  }
}
