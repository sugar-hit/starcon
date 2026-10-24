package com.starcon.finance;

import static com.starcon.finance.ReconciliationTaskModels.*;

import com.starcon.finance.BankReconciliationPort.BankStatementLine;
import com.starcon.finance.ReconciliationMatcher.Group;
import com.starcon.finance.ReconciliationMatcher.Item;
import com.starcon.finance.ReconciliationMatcher.NearMiss;
import com.starcon.finance.ReconciliationMatcher.Pair;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
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
 * 12.3.2.1 对账任务。
 *
 * <p>蓝图 12-finance.md：「发起与执行对账任务：选择对账范围（店铺 + 周期 + 账户）、
 * 运行自动匹配、输出匹配结果与差异。」输入：「对账范围；我方账数据（结算、应收、
 * 应付、银行流水）；平台账单。」输出：「匹配结果（一对一、一对多、多对一）+ 差异清单。」
 * 关系：「上游 = 12.3.1.1、12.7.1.2；下游 = 12.3.2.2。
 * <b>不允许『金额相同即匹配』</b>——金额相同只是候选，必须结合订单号、日期、类型。」
 *
 * <p><b>本模块的两侧</b>：
 *
 * <ul>
 *   <li><b>平台账单侧（EXTERNAL）</b> = 12.3.1.1 的结算单头 + 12.3.1.2 的结算明细行
 *       —— 平台结算报告 / 平台交易报告的落地，<b>就是平台自己的记录</b>。</li>
 *   <li><b>我方账侧（BOOK）</b> = 12.1.1.1 应收明细行 / 12.2.2.1 付款单明细行 /
 *       12.7.1.2 银行流水行（<b>经授权只读 API</b>）。</li>
 * </ul>
 *
 * <p>⚠️ <b>「结算」不在我方账侧。</b>蓝图输入句把「结算」列在「我方账数据」里，
 * 但 12.3.1.1 的结算单 / 12.3.1.2 的结算明细行**就是平台账单的落地**（平台结算报告 +
 * 平台交易报告）—— 拿它当「我方账」会让**同一份平台文件的两份投影互相对撞**
 * （结算单头 $105 ⇄ 自己的明细行 $105 ⇒ 假匹配 + 假差异）。本模块把它放在
 * **平台侧**，这既符合蓝图上游「上游 = 12.3.1.1」，也让「结算单到账 ⇄ 银行流水」
 * 这条最有价值的核对线成立（结算单头在平台侧 ⇄ 银行流水在我方账侧）。
 *
 * <p><b>五条口径纪律</b>（每一条都有实现，不是口号）：
 *
 * <ol>
 *   <li><b>不允许「金额相同即匹配」。</b>见 {@link ReconciliationMatcher}：
 *       候选生成**完全不看金额**（必须对上订单号 / 日期 / 类型中的**至少两个**），
 *       金额相等是**成组**的必要条件；DB 层用 CHECK 再钉一道。</li>
 *   <li><b>匹配结果带上匹配依据。</b>每一行都记着比对了哪几个维度（机器可读）
 *       + 人话说明 ⇒ 页面能说清「为什么这两行配成一对」。</li>
 *   <li><b>一对多 / 多对一落成多行。</b>一行 = 一个 (平台账单行, 我方账行) 对；
 *       同组共享 groupKey / groupNo / shape。</li>
 *   <li><b>跨币种绝不相加。</b>差异清单**按币种分组**；汇率不可得就如实报
 *       「汇率不可得」（差额恒为 {@code null}），**不按 1:1 兜底**。</li>
 *   <li><b>执行结果是一次性事实。</b>任务推进到 MATCHED 之后范围冻结、
 *       匹配结果 append-only ⇒ <b>重跑 = 新建一个任务</b>。</li>
 * </ol>
 */
@Service
public class ReconciliationTaskService {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ReconciliationTaskService.class);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 任务号前缀。 */
  static final String TASK_NO_PREFIX = "RT-";

  /** 我方账来源词表。 */
  static final List<String> BOOK_SOURCES =
      List.of("RECEIVABLE", "PAYMENT_ORDER", "BANK_STATEMENT");

  private final DSLContext sql;
  private final ChannelShopPort shopPort;

  public ReconciliationTaskService(DSLContext sql, ChannelShopPort shopPort) {
    this.sql = sql;
    this.shopPort = shopPort;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 必须把「本模块没接」与「上游没交付」分开写。
   */
  public FinanceReconCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceReconCapability>();
    var gaps = new ArrayList<FinanceReconGap>();

    capabilities.add(
        new FinanceReconCapability(
            "12.3.2.1",
            "对账任务（范围 + 执行）",
            true,
            "finance_reconciliation_task + finance_reconciliation_match"
                + " + finance_reconciliation_difference + finance_reconciliation_event",
            "",
            "范围 = **店铺 + 周期 + 账户**（账户取自 12.7.1.1 的真实账户，"
                + "币种与法人**继承账户**；店铺按服务端授权范围过滤，"
                + "**不信任客户端传的范围参数**）。"
                + "任务状态机只有 DRAFT → MATCHED 两步；"
                + "执行完成后**范围冻结、匹配结果 append-only** ⇒ "
                + "**重跑 = 新建一个任务**，历史不被改写。"));

    capabilities.add(
        new FinanceReconCapability(
            "12.3.2.1",
            "自动匹配（金额相同即匹配的硬约束）",
            true,
            "ReconciliationMatcher（纯逻辑）+ DB CHECK",
            "",
            "🔴 候选生成**完全不看金额**：两个条目必须对上"
                + "**订单号 / 日期 / 类型中的至少两个**，且同币种、同方向。"
                + "金额相等是**成组**的必要条件（组内平台合计 = 组内我方合计），"
                + "不是候选条件。DB 层 `matched_dimensions` 的 CHECK 再钉一道："
                + "至少含两个 {REFERENCE, DATE, TYPE} ⇒ "
                + "「只看金额就配成一对」在结构上写不出来。"));

    capabilities.add(
        new FinanceReconCapability(
            "12.3.2.1",
            "匹配结果（一对一 / 一对多 / 多对一）",
            true,
            "finance_reconciliation_match（**多行**）",
            "",
            "一行 = 一个 (平台账单行, 我方账行) 对；同组共享 groupKey / groupNo / shape。"
                + "ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE / MANY_TO_MANY 四种形态"
                + "**明确区分**；多张单据对一张落成**多行**，"
                + "**不把多张单据揉成一条**。每行带匹配依据（比对了哪几个维度 + 人话说明）。"));

    capabilities.add(
        new FinanceReconCapability(
            "12.3.2.1",
            "差异清单（按币种分组）",
            true,
            "finance_reconciliation_difference",
            "",
            "五种差异：平台账单多出 / 我方账多出 / 金额对不齐 / 币种不一致 / 维度对不上。"
                + "🔴 **跨币种绝不相加**：账户单一币种 ⇒ 清单**按币种分组**；"
                + "币种不一致时**差额恒为 null**（汇率不可得，"
                + "**不按 1:1 兜底** —— 写 0 会被读成「账是平的」）。"
                + "「维度对不上」是「金额相同只是候选」的**可观测证据**："
                + "两笔金额一样但只对上 1 个维度 ⇒ 明确告诉你没配上、差在哪。"));

    capabilities.add(
        new FinanceReconCapability(
            "12.7.1.2",
            "银行流水（经授权只读 API）",
            true,
            "BankReconciliationPort → GET /finance/bank-reconciliation/**",
            "",
            "🔴 **走授权只读 API，不碰那九张表**（它的写入所有权）："
                + "本模块对 `finance_bank_recon_*` **零 SELECT / 零 INSERT / 零 UPDATE / 零 DELETE / "
                + "零 FK / 零触发器** —— 连存在性校验都不做（连校验都是跨模块耦合）。"
                + "转发的是**调用方自己的** bearer ⇒ 租户隔离是两道"
                + "（本模块 RLS + 12.7.1.2 RLS）。"));

    capabilities.add(
        new FinanceReconCapability(
            "12.3.2.1",
            "差异处理（认领 / 调整 / 核销）",
            false,
            "下游模块未交付",
            "12.3.2.2",
            "⇒ 本模块**只输出差异清单**（差异项 + 差额 + 归属），"
                + "**不实现**认领 / 调整 / 核销。差异行的 `status` 词表已预留，"
                + "下游推进时由 DB 触发器放行（其余字段冻结）。"));

    capabilities.add(
        new FinanceReconCapability(
            "12.3.2.3",
            "匹配规则配置",
            false,
            "规则管理表**刻意不建**（那是 12.3.2.3 的表）",
            "12.3.2.3",
            "⇒ 匹配参数是**代码内受控词表 + 接口入参**"
                + "（`dateWindowDays` 1..31 / `amountTolerance` 默认 0），"
                + "随任务**快照**进任务行。规则配置化之后，候选生成与容差"
                + "应改成读规则表 —— 但那要 12.3.2.3 先交付。"));

    gaps.add(
        new FinanceReconGap(
            "12.3.2.2", "差异处理", "finance", false, "12.3 结算与对账",
            "下游未交付：它接本模块的差异清单做认领 / 调整 / 核销。"));

    gaps.add(
        new FinanceReconGap(
            "12.3.2.3", "自动匹配规则", "finance", false, "12.3 结算与对账",
            "下游未交付：它接本模块的历史匹配结果做规则调优。"));

    gaps.add(
        new FinanceReconGap(
            "12.2.1.1", "收款单引用受管资金账户", "finance", false, "12 财务核算",
            "上游未接线：收款单 / 付款单与资金账户的引用尚未打通，"
                + "本模块的候选集按「账户币种 + 周期」取。"));

    return new FinanceReconCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：词表 + **真实数据**（资金账户取自 12.7.1.1）。 */
  public FinanceReconOptions options() {
    var accounts =
        sql.fetch(
                "select id,account_no,account_name,account_type,currency,legal_entity_name"
                    + " from finance_fund_account where status = 'ACTIVE' order by account_no")
            .map(
                r ->
                    new FinanceReconAccount(
                        r.get("id", UUID.class),
                        r.get("account_no", String.class),
                        r.get("account_name", String.class),
                        r.get("account_type", String.class),
                        r.get("currency", String.class),
                        r.get("legal_entity_name", String.class)));

    var currencies =
        sql.fetch("select distinct currency from finance_fund_account order by currency")
            .map(r -> r.get(0, String.class));

    return new FinanceReconOptions(
        List.of(
            new FinanceReconOption(
                FinanceReconTaskStatus.DRAFT.name(), FinanceReconTaskStatus.DRAFT.label()),
            new FinanceReconOption(
                FinanceReconTaskStatus.MATCHED.name(), FinanceReconTaskStatus.MATCHED.label())),
        List.of(
            new FinanceReconOption("RECEIVABLE", "应收明细（12.1.1.1）"),
            new FinanceReconOption("PAYMENT_ORDER", "付款单明细（12.2.2.1）"),
            new FinanceReconOption("BANK_STATEMENT", "银行流水（12.7.1.2，授权只读 API）")),
        List.of(
            new FinanceReconOption(
                ReconciliationMatcher.ONE_TO_ONE, "一对一（1 平台行 ⇄ 1 我方行）"),
            new FinanceReconOption(
                ReconciliationMatcher.ONE_TO_MANY, "一对多（1 平台行 ⇄ N 我方行）"),
            new FinanceReconOption(
                ReconciliationMatcher.MANY_TO_ONE, "多对一（N 平台行 ⇄ 1 我方行）"),
            new FinanceReconOption(
                ReconciliationMatcher.MANY_TO_MANY, "多对多（双方都拆）")),
        List.of(
            new FinanceReconOption(
                FinanceReconDifferenceType.EXTERNAL_ONLY.name(),
                FinanceReconDifferenceType.EXTERNAL_ONLY.label()),
            new FinanceReconOption(
                FinanceReconDifferenceType.BOOK_ONLY.name(),
                FinanceReconDifferenceType.BOOK_ONLY.label()),
            new FinanceReconOption(
                FinanceReconDifferenceType.AMOUNT_MISMATCH.name(),
                FinanceReconDifferenceType.AMOUNT_MISMATCH.label()),
            new FinanceReconOption(
                FinanceReconDifferenceType.CURRENCY_MISMATCH.name(),
                FinanceReconDifferenceType.CURRENCY_MISMATCH.label()),
            new FinanceReconOption(
                FinanceReconDifferenceType.DIMENSION_MISMATCH.name(),
                FinanceReconDifferenceType.DIMENSION_MISMATCH.label())),
        List.of(
            new FinanceReconOption(FinanceReconDirection.IN.name(), FinanceReconDirection.IN.label()),
            new FinanceReconOption(
                FinanceReconDirection.OUT.name(), FinanceReconDirection.OUT.label())),
        accounts,
        currencies,
        "账户候选取自 **12.7.1.1 的真实账户档案**（不是常量）；"
            + "币种取自真实账户。任务 / 匹配形态 / 差异类型 / 方向都是**受控词表**。"
            + "⚠️ 「差异类型」里的「维度对不上」= 两笔金额一样、"
            + "但只对上 1 个维度 ⇒ **不构成匹配**（金额相同只是候选）。");
  }

  // ================================================================ 任务

  /** 对账任务列表（真分页 + 真筛选 + 真搜索）。 */
  public FinanceReconTaskPage listTasks(
      UUID accountId, UUID shopId, String status, String keyword, int page, int size) {
    var pageNo = Math.max(0, page);
    var pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();

    if (accountId != null) {
      where.append(" and account_id = ?");
      args.add(accountId);
    }
    if (shopId != null) {
      where.append(" and shop_id = ?");
      args.add(shopId);
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      args.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      var kw = keyword.trim().toLowerCase(Locale.ROOT);
      where.append(
          " and (position(? in lower(task_no)) > 0"
              + " or position(? in lower(account_no)) > 0"
              + " or position(? in lower(account_name)) > 0"
              + " or position(? in lower(shop_name)) > 0"
              + " or position(? in lower(note)) > 0)");
      args.add(kw);
      args.add(kw);
      args.add(kw);
      args.add(kw);
      args.add(kw);
    }

    var total =
        sql.fetchOne("select count(*) from finance_reconciliation_task" + where, args.toArray())
            .get(0, Long.class);

    var bindings = new ArrayList<Object>(args);
    bindings.add(pageSize);
    bindings.add(pageNo * pageSize);
    var rows =
        sql.fetch(
            "select id,task_no,account_id,account_no,account_name,currency,legal_entity_name,"
                + " shop_id,shop_name,platform_code,period_start,period_end,book_sources,"
                + " date_window_days,amount_tolerance,status,external_line_count,"
                + " matched_group_count,matched_line_count,difference_count,matched_at,note,"
                + " version,created_at,updated_at from finance_reconciliation_task"
                + where
                + " order by period_end desc, task_no desc, id asc limit ? offset ?",
            bindings.toArray());

    return new FinanceReconTaskPage(rows.map(this::taskRow), pageNo, pageSize, total);
  }

  /** 任务详情（任务 + 汇总）。 */
  public FinanceReconTaskDetail detail(UUID id) {
    var task = taskRow(requireTask(id));
    return new FinanceReconTaskDetail(task, summary(id));
  }

  /**
   * 建任务。
   *
   * <p>⚠️ 四道闸：① 账户必须存在且**启用中**；② 币种**继承账户**（单账户单币种）；
   * ③ 店铺必须**在调用方的授权范围内**（服务端按授权过滤，不信任客户端参数）；
   * ④ 我方账来源必须是受控词表的非空子集。
   */
  @Transactional
  public FinanceReconTaskRow createTask(
      FinanceReconTaskCreateInput input, String bearer, AccessService.Access actor) {
    var scope =
        resolveScope(
            input.accountId(),
            input.shopId(),
            input.periodStart(),
            input.periodEnd(),
            input.bookSources(),
            input.dateWindowDays(),
            input.amountTolerance(),
            bearer);
    var id = UUID.randomUUID();
    var now = nowUtc();

    insert(
        "finance_reconciliation_task",
        List.of(
            column("id", id),
            column("tenant_id", actor.tenantId()),
            column("task_no", taskNo()),
            column("account_id", scope.accountId()),
            column("account_no", scope.accountNo()),
            column("account_name", scope.accountName()),
            column("currency", scope.currency()),
            column("legal_entity_id", scope.legalEntityId()),
            column("legal_entity_code", scope.legalEntityCode()),
            column("legal_entity_name", scope.legalEntityName()),
            column("shop_id", scope.shopId()),
            column("shop_code", scope.shopCode()),
            column("shop_name", scope.shopName()),
            column("platform_code", scope.platformCode()),
            column("period_start", scope.periodStart(), "::date"),
            column("period_end", scope.periodEnd(), "::date"),
            column("book_sources", pgTextArray(scope.bookSources()), "::text[]"),
            column("date_window_days", scope.dateWindowDays()),
            column("amount_tolerance", scope.amountTolerance()),
            column("status", "DRAFT"),
            column("external_line_count", 0),
            column("matched_group_count", 0),
            column("matched_line_count", 0),
            column("difference_count", 0),
            column("note", trim(input.note())),
            column("version", 0),
            column("created_by", actor.userId()),
            column("created_at", now, "::timestamptz"),
            column("updated_at", now, "::timestamptz")));

    appendEvent(
        actor.tenantId(),
        id,
        "TASK_CREATED",
        "DRAFT",
        "DRAFT",
        "建立对账任务 "
            + scope.accountNo()
            + " / "
            + scope.periodStart()
            + " ~ "
            + scope.periodEnd()
            + "（我方账来源："
            + String.join("、", scope.bookSources())
            + "）",
        Map.of(
            "accountNo", scope.accountNo(),
            "periodStart", scope.periodStart().toString(),
            "periodEnd", scope.periodEnd().toString(),
            "bookSources", scope.bookSources()),
        actor.userId(),
        now);

    return taskRow(requireTask(id));
  }

  /** 改范围（**只限 DRAFT 态**；已执行的任务范围冻结）。 */
  @Transactional
  public FinanceReconTaskRow updateTask(
      UUID id, FinanceReconTaskUpdateInput input, String bearer, AccessService.Access actor) {
    var task = requireTask(id);
    if (!"DRAFT".equals(task.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "对账任务已执行完成，范围不可改（要重来请新建一个任务）");
    }
    var scope =
        resolveScope(
            input.accountId(),
            input.shopId(),
            input.periodStart(),
            input.periodEnd(),
            input.bookSources(),
            input.dateWindowDays(),
            input.amountTolerance(),
            bearer);
    var now = nowUtc();

    sql.execute(
        "update finance_reconciliation_task set account_id = ?, account_no = ?,"
            + " account_name = ?, currency = ?, legal_entity_id = ?, legal_entity_code = ?,"
            + " legal_entity_name = ?, shop_id = ?, shop_code = ?, shop_name = ?, platform_code = ?,"
            + " period_start = ?::date, period_end = ?::date, book_sources = ?::text[],"
            + " date_window_days = ?, amount_tolerance = ?, note = ?, version = version + 1,"
            + " updated_at = ?::timestamptz where id = ? and tenant_id = ?",
        scope.accountId(),
        scope.accountNo(),
        scope.accountName(),
        scope.currency(),
        scope.legalEntityId(),
        scope.legalEntityCode(),
        scope.legalEntityName(),
        scope.shopId(),
        scope.shopCode(),
        scope.shopName(),
        scope.platformCode(),
        scope.periodStart(),
        scope.periodEnd(),
        pgTextArray(scope.bookSources()),
        scope.dateWindowDays(),
        scope.amountTolerance(),
        trim(input.note()),
        now,
        id,
        actor.tenantId());

    appendEvent(
        actor.tenantId(),
        id,
        "TASK_SCOPE_UPDATED",
        "DRAFT",
        "DRAFT",
        "改动对账范围：" + scope.accountNo() + " / " + scope.periodStart() + " ~ " + scope.periodEnd(),
        Map.of("periodStart", scope.periodStart().toString(), "periodEnd", scope.periodEnd().toString()),
        actor.userId(),
        now);

    return taskRow(requireTask(id));
  }

  // ================================================================ 执行匹配

  /** 对账范围（控制器在**开启事务之前**用它去取跨模块数据）。 */
  public record ReconScope(
      UUID taskId,
      UUID accountId,
      String accountNo,
      String currency,
      UUID shopId,
      LocalDate periodStart,
      LocalDate periodEnd,
      List<String> bookSources,
      int dateWindowDays,
      BigDecimal amountTolerance) {}

  /** 读任务范围（供控制器在事务外取跨模块数据）。 */
  public ReconScope scopeOf(UUID id) {
    var task = requireTask(id);
    return new ReconScope(
        task.get("id", UUID.class),
        task.get("account_id", UUID.class),
        task.get("account_no", String.class),
        task.get("currency", String.class),
        task.get("shop_id", UUID.class),
        task.get("period_start", LocalDate.class),
        task.get("period_end", LocalDate.class),
        stringList(task.get("book_sources", String[].class)),
        task.get("date_window_days", Integer.class),
        task.get("amount_tolerance", BigDecimal.class));
  }

  /**
   * 跑自动匹配。
   *
   * <p>⚠️ <b>只允许跑一次</b>：任务已 MATCHED ⇒ 409。**重跑 = 新建一个任务**。
   */
  @Transactional
  public FinanceReconMatchRunResult runMatch(
      UUID id, ReconScope scope, List<BankStatementLine> bankLines, AccessService.Access actor) {
    try {
      return runMatchInternal(id, scope, bankLines, actor);
    } catch (RuntimeException e) {
      // ⚠️ 全局错误翻译（ApiErrorHandler）会把数据库/参数错误压成一句人话，
      //   **不带原因** ⇒ 这里先留一条带堆栈的日志，否则线上只能看到「409 冲突」
      //   而看不到到底哪条 SQL 出的问题。
      LOG.error("对账任务 {} 跑匹配失败：{}", id, e.getMessage(), e);
      throw e;
    }
  }

  private FinanceReconMatchRunResult runMatchInternal(
      UUID id, ReconScope scope, List<BankStatementLine> bankLines, AccessService.Access actor) {
    var task = requireTask(id);
    if (!"DRAFT".equals(task.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "对账任务已执行完成，匹配结果不可改写（要重来请新建一个任务）："
              + task.get("task_no", String.class));
    }

    var windowDays = scope.dateWindowDays();
    var tolerance = scope.amountTolerance();
    var periodStart = scope.periodStart();
    var periodEnd = scope.periodEnd();

    // ---- 1. 平台账单侧（12.3.1.1 结算单头 + 12.3.1.2 结算明细行）----
    var external = externalItems(scope.shopId(), periodStart, periodEnd);

    // ---- 2. 我方账侧：逐个来源独立匹配（不同来源之间**不互配**）----
    var groups = new ArrayList<Group>();
    var differences = new ArrayList<DifferenceDraft>();
    var usedExternalIds = new HashSet<UUID>();
    var groupNo = 0;

    for (var source : scope.bookSources()) {
      var book = bookItems(source, scope, bankLines, windowDays);
      if (book.isEmpty()) {
        continue;
      }
      var outcome = ReconciliationMatcher.match(external, book, windowDays, tolerance);
      for (var group : outcome.groups()) {
        groupNo++;
        for (var pair : group.pairs()) {
          usedExternalIds.add(pair.external().id());
        }
        // ⚠️ **没配平的组不进匹配结果表**（那是对账任务的事实：匹配 = 配平）。
        //   它落成 AMOUNT_MISMATCH 差异（见下）。DB 层也有同一道闸
        //   （guard_finance_reconciliation_match_group_amount）。
        if (!group.balanced()) {
          continue;
        }
        groups.add(
            new Group(
                group.groupKey(),
                groupNo,
                group.shape(),
                group.pairs(),
                group.externalAmount(),
                group.bookAmount(),
                group.difference(),
                group.balanced()));
      }
      // 组内没配平的 ⇒ 落成 AMOUNT_MISMATCH 差异。
      for (var group : outcome.groups()) {
        if (group.balanced()) {
          continue;
        }
        var first = group.pairs().get(0);
        differences.add(
            new DifferenceDraft(
                "AMOUNT_MISMATCH",
                "BOTH",
                source,
                group.groupKey(),
                first.external().kind(),
                first.external().id(),
                null,
                first.external().date(),
                first.external().reference(),
                first.external().type(),
                first.external().currency(),
                first.external().direction(),
                group.externalAmount(),
                group.bookAmount(),
                group.difference(),
                true,
                attributionOf(group),
                "两边按订单号 / 日期 / 类型配上了，但组内合计不等：平台 "
                    + money(group.externalAmount())
                    + " ≠ 我方 "
                    + money(group.bookAmount())
                    + "（差 "
                    + money(group.difference())
                    + "）"));
      }
      // 近似对（金额相同、只对上 1 个维度）⇒ 维度对不上。
      for (var nearMiss : outcome.nearMisses()) {
        usedExternalIds.add(nearMiss.external().id());
        differences.add(
            new DifferenceDraft(
                "DIMENSION_MISMATCH",
                "BOTH",
                source,
                null,
                nearMiss.external().kind(),
                nearMiss.external().id(),
                nearMiss.book().id(),
                nearMiss.external().date(),
                nearMiss.external().reference(),
                nearMiss.external().type(),
                nearMiss.external().currency(),
                nearMiss.external().direction(),
                nearMiss.external().amount(),
                nearMiss.book().amount(),
                nearMiss.book().amount().subtract(nearMiss.external().amount()),
                true,
                attributionOf(nearMiss),
                "金额相同，但只对上 "
                    + nearMiss.dimensions().size()
                    + " 个维度（"
                    + String.join("、", nearMiss.dimensions())
                    + "）⇒ **不构成匹配**"
                    + "（金额相同只是候选，必须结合订单号、日期、类型）"));
      }
      // 我方账多出来的（周期内）。
      for (var item : outcome.unmatchedBook()) {
        if (item.date() != null
            && (item.date().isBefore(periodStart) || item.date().isAfter(periodEnd))) {
          continue;
        }
        differences.add(
            new DifferenceDraft(
                "BOOK_ONLY",
                "BOOK",
                source,
                null,
                null,
                null,
                item.id(),
                item.date(),
                item.reference(),
                item.type(),
                item.currency(),
                item.direction(),
                null,
                item.amount(),
                item.amount(),
                true,
                item.attribution(),
                "我方账有、平台账单没有（周期 "
                    + periodStart
                    + " ~ "
                    + periodEnd
                    + " 内的 "
                    + source
                    + " 记录）"));
      }
    }

    // ---- 3. 平台账单多出来的（跨来源去重：被任一来源配上的就不算差异）----
    for (var item : external) {
      if (usedExternalIds.contains(item.id())) {
        continue;
      }
      if (item.date() != null
          && (item.date().isBefore(periodStart) || item.date().isAfter(periodEnd))) {
        continue;
      }
      if (!scope.currency().equals(item.currency())) {
        // 🔴 币种不一致 ⇒ 汇率不可得 ⇒ 差额恒为 null（**不按 1:1 兜底**）。
        differences.add(
            new DifferenceDraft(
                "CURRENCY_MISMATCH",
                "EXTERNAL",
                null,
                null,
                item.kind(),
                item.id(),
                null,
                item.date(),
                item.reference(),
                item.type(),
                item.currency(),
                item.direction(),
                item.amount(),
                null,
                null,
                false,
                item.attribution(),
                "平台账单的币种 "
                    + item.currency()
                    + " 与对账账户的币种 "
                    + scope.currency()
                    + " 不一致 ⇒ **汇率不可得**（本模块不折算、不按 1:1 兜底）"));
        continue;
      }
      differences.add(
          new DifferenceDraft(
              "EXTERNAL_ONLY",
              "EXTERNAL",
              null,
              null,
              item.kind(),
              item.id(),
              null,
              item.date(),
              item.reference(),
              item.type(),
              item.currency(),
              item.direction(),
              item.amount(),
              null,
              item.amount().negate(),
              true,
              item.attribution(),
              "平台账单有、我方账没有（周期 "
                  + periodStart
                  + " ~ "
                  + periodEnd
                  + " 内的 "
                  + item.kind()
                  + "）"));
    }

    // ---- 4. 落库 ----
    var now = nowUtc();
    for (var group : groups) {
      for (var pair : group.pairs()) {
        var dimensions = ReconciliationMatcher.orderedDimensions(pair.dimensions());
        insertMatch(id, actor.tenantId(), group, pair, dimensions, actor.userId(), now);
      }
    }
    for (var draft : differences) {
      insertDifference(id, actor.tenantId(), draft, now);
    }

    var matchedLineCount = groups.stream().mapToInt(group -> group.pairs().size()).sum();
    var oneToOne =
        groups.stream().filter(g -> ReconciliationMatcher.ONE_TO_ONE.equals(g.shape())).count();
    var oneToMany =
        groups.stream().filter(g -> ReconciliationMatcher.ONE_TO_MANY.equals(g.shape())).count();
    var manyToOne =
        groups.stream().filter(g -> ReconciliationMatcher.MANY_TO_ONE.equals(g.shape())).count();
    var manyToMany =
        groups.stream().filter(g -> ReconciliationMatcher.MANY_TO_MANY.equals(g.shape())).count();

    sql.execute(
        "update finance_reconciliation_task set status = 'MATCHED',"
            + " external_line_count = ?, matched_group_count = ?, matched_line_count = ?,"
            + " difference_count = ?, matched_at = ?::timestamptz, matched_by = ?,"
            + " version = version + 1, updated_at = ?::timestamptz where id = ? and tenant_id = ?",
        external.size(),
        groups.size(),
        matchedLineCount,
        differences.size(),
        now,
        actor.userId(),
        now,
        id,
        actor.tenantId());

    appendEvent(
        actor.tenantId(),
        id,
        "MATCH_COMPLETED",
        "DRAFT",
        "MATCHED",
        "完成自动匹配：平台账单 "
            + external.size()
            + " 行 → 匹配组 "
            + groups.size()
            + " 个（匹配行 "
            + matchedLineCount
            + "）、差异 "
            + differences.size()
            + " 条",
        Map.of(
            "externalLineCount", external.size(),
            "matchedGroupCount", groups.size(),
            "matchedLineCount", matchedLineCount,
            "differenceCount", differences.size(),
            "oneToOne", oneToOne,
            "oneToMany", oneToMany,
            "manyToOne", manyToOne,
            "manyToMany", manyToMany),
        actor.userId(),
        now);

    return new FinanceReconMatchRunResult(
        id,
        task.get("task_no", String.class),
        external.size(),
        groups.size(),
        matchedLineCount,
        differences.size(),
        oneToOne,
        oneToMany,
        manyToOne,
        manyToMany,
        scope.bookSources().size(),
        differences.size(),
        now.toString(),
        "🔴 候选生成**完全不看金额**：必须对上订单号 / 日期 / 类型中的至少两个。"
            + "金额相等只是**成组**的必要条件（组内合计相等）。");
  }

  // ================================================================ 匹配结果 / 差异 / 事件

  /** 匹配结果（真分页 + 真筛选）。 */
  public FinanceReconMatchPage matches(
      UUID id, String shape, String bookSource, int page, int size) {
    requireTask(id);
    var pageNo = Math.max(0, page);
    var pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    var where = new StringBuilder(" where task_id = ?");
    var args = new ArrayList<Object>();
    args.add(id);
    if (shape != null && !shape.isBlank()) {
      where.append(" and shape = ?");
      args.add(shape.trim().toUpperCase(Locale.ROOT));
    }
    if (bookSource != null && !bookSource.isBlank()) {
      where.append(" and book_source = ?");
      args.add(bookSource.trim().toUpperCase(Locale.ROOT));
    }

    var total =
        sql.fetchOne("select count(*) from finance_reconciliation_match" + where, args.toArray())
            .get(0, Long.class);

    var bindings = new ArrayList<Object>(args);
    bindings.add(pageSize);
    bindings.add(pageNo * pageSize);
    var rows =
        sql.fetch(
            "select id,task_id,group_key,group_no,shape,book_source,external_kind,"
                + " external_line_id,external_no,external_reference,external_type,"
                + " external_occurred_on,external_amount,book_line_id,book_no,book_reference,"
                + " book_type,book_occurred_on,book_amount,currency,direction,"
                + " group_external_amount,group_book_amount,group_amount_difference,"
                + " matched_dimensions,dimension_count,match_basis,day_difference,matched_at"
                + " from finance_reconciliation_match"
                + where
                + " order by group_no, external_line_id, book_line_id limit ? offset ?",
            bindings.toArray());

    return new FinanceReconMatchPage(rows.map(this::matchRow), pageNo, pageSize, total);
  }

  /** 差异清单（真分页 + 真筛选：类型 / 币种 / 侧）。 */
  public FinanceReconDifferencePage differences(
      UUID id, String differenceType, String currency, String side, int page, int size) {
    requireTask(id);
    var pageNo = Math.max(0, page);
    var pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    var where = new StringBuilder(" where task_id = ?");
    var args = new ArrayList<Object>();
    args.add(id);
    if (differenceType != null && !differenceType.isBlank()) {
      where.append(" and difference_type = ?");
      args.add(differenceType.trim().toUpperCase(Locale.ROOT));
    }
    if (currency != null && !currency.isBlank()) {
      where.append(" and currency = ?");
      args.add(currency.trim().toUpperCase(Locale.ROOT));
    }
    if (side != null && !side.isBlank()) {
      where.append(" and side = ?");
      args.add(side.trim().toUpperCase(Locale.ROOT));
    }

    var total =
        sql.fetchOne(
                "select count(*) from finance_reconciliation_difference" + where, args.toArray())
            .get(0, Long.class);

    var bindings = new ArrayList<Object>(args);
    bindings.add(pageSize);
    bindings.add(pageNo * pageSize);
    var rows =
        sql.fetch(
            "select id,task_id,difference_type,side,book_source,group_key,external_kind,"
                + " external_line_id,book_line_id,occurred_on,reference,transaction_type,"
                + " external_amount,book_amount,currency,direction,difference_amount,"
                + " exchange_rate_available,attribution,reason,status"
                + " from finance_reconciliation_difference"
                + where
                + " order by currency, difference_type, id limit ? offset ?",
            bindings.toArray());

    return new FinanceReconDifferencePage(rows.map(this::differenceRow), pageNo, pageSize, total);
  }

  /** 事件流（真分页；append-only 举证）。 */
  public FinanceReconEventPage events(UUID id, int page, int size) {
    try {
      return eventsInternal(id, page, size);
    } catch (RuntimeException e) {
      LOG.error("读对账任务 {} 的事件流失败：{}", id, e.getMessage(), e);
      throw e;
    }
  }

  private FinanceReconEventPage eventsInternal(UUID id, int page, int size) {
    requireTask(id);
    var pageNo = Math.max(0, page);
    var pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    var total =
        sql.fetchOne("select count(*) from finance_reconciliation_event where task_id = ?", id)
            .get(0, Long.class);

    var rows =
        sql.fetch(
            "select id,task_id,event_type,from_status,to_status,summary,actor_id,occurred_at"
                + " from finance_reconciliation_event where task_id = ?"
                + " order by occurred_at desc, id desc limit ? offset ?",
            id,
            pageSize,
            pageNo * pageSize);

    return new FinanceReconEventPage(rows.map(this::eventRow), pageNo, pageSize, total);
  }

  // ================================================================ 汇总（按币种分组）

  /**
   * 任务汇总。
   *
   * <p>🔴 <b>按币种分组，绝不跨币种合计。</b>
   */
  public FinanceReconTaskSummary summary(UUID id) {
    var task = requireTask(id);

    var shapeCount =
        sql.fetch(
                "select shape, count(*) as n from finance_reconciliation_match"
                    + " where task_id = ? group by shape",
                id)
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    r -> r.get("shape", String.class), r -> r.get("n", Long.class), (a, b) -> a));

    // 匹配组（按组去重：一行一对，直接求和会重复计）。
    var groups =
        sql.fetch(
            "select distinct group_key, currency, group_external_amount, group_book_amount"
                + " from finance_reconciliation_match where task_id = ? order by group_key",
            id);

    var matchedExternalByCurrency = new LinkedHashMap<String, BigDecimal>();
    var matchedBookByCurrency = new LinkedHashMap<String, BigDecimal>();
    var matchedGroupCountByCurrency = new LinkedHashMap<String, Long>();
    for (var row : groups) {
      var currency = row.get("currency", String.class);
      matchedExternalByCurrency.merge(
          currency, row.get("group_external_amount", BigDecimal.class), BigDecimal::add);
      matchedBookByCurrency.merge(
          currency, row.get("group_book_amount", BigDecimal.class), BigDecimal::add);
      matchedGroupCountByCurrency.merge(currency, 1L, Long::sum);
    }

    // 差异侧（未配平的条目都在这里）。
    var differenceByCurrency = new LinkedHashMap<String, BigDecimal>();
    var differenceCountByCurrency = new LinkedHashMap<String, Long>();
    var externalOnlyByCurrency = new LinkedHashMap<String, BigDecimal>();
    var bookOnlyByCurrency = new LinkedHashMap<String, BigDecimal>();
    var rateMissingByCurrency = new LinkedHashMap<String, Boolean>();
    var diffRows =
        sql.fetch(
            "select currency, difference_type, difference_amount, exchange_rate_available,"
                + " external_amount, book_amount from finance_reconciliation_difference"
                + " where task_id = ?",
            id);
    for (var row : diffRows) {
      var currency = row.get("currency", String.class);
      differenceCountByCurrency.merge(currency, 1L, Long::sum);
      if (!row.get("exchange_rate_available", Boolean.class)) {
        rateMissingByCurrency.merge(currency, Boolean.TRUE, (a, b) -> a || b);
      } else {
        var delta = row.get("difference_amount", BigDecimal.class);
        if (delta != null) {
          differenceByCurrency.merge(currency, delta, BigDecimal::add);
        }
      }
      var type = row.get("difference_type", String.class);
      var externalAmount = row.get("external_amount", BigDecimal.class);
      var bookAmount = row.get("book_amount", BigDecimal.class);
      if (externalAmount != null && !"BOOK_ONLY".equals(type)) {
        externalOnlyByCurrency.merge(currency, externalAmount, BigDecimal::add);
      }
      if (bookAmount != null && !"EXTERNAL_ONLY".equals(type)) {
        bookOnlyByCurrency.merge(currency, bookAmount, BigDecimal::add);
      }
    }

    var currencies = new TreeSet<String>();
    currencies.addAll(matchedExternalByCurrency.keySet());
    currencies.addAll(matchedBookByCurrency.keySet());
    currencies.addAll(differenceCountByCurrency.keySet());

    var accountCurrency = task.get("currency", String.class);
    var summaries = new ArrayList<FinanceReconCurrencySummary>();
    for (var currency : currencies) {
      var external =
          sum(matchedExternalByCurrency.get(currency), externalOnlyByCurrency.get(currency));
      var book = sum(matchedBookByCurrency.get(currency), bookOnlyByCurrency.get(currency));
      var difference = differenceByCurrency.get(currency);
      var rateAvailable = !Boolean.TRUE.equals(rateMissingByCurrency.get(currency));
      summaries.add(
          new FinanceReconCurrencySummary(
              currency,
              rateAvailable,
              external,
              book,
              difference,
              matchedGroupCountByCurrency.getOrDefault(currency, 0L),
              differenceCountByCurrency.getOrDefault(currency, 0L),
              currency.equals(accountCurrency)
                  ? "对账账户的币种：该币种内部金额可相加（账户单一币种 ⇒ 无跨币种折算）"
                  : "⚠️ 与对账账户的币种不同：**汇率不可得**，本模块不折算、不按 1:1 兜底；"
                      + "该币种的金额**单独成组**，绝不并进其它币种的合计"));
    }

    var topDifferences =
        sql.fetch(
                "select id,task_id,difference_type,side,book_source,group_key,external_kind,"
                    + " external_line_id,book_line_id,occurred_on,reference,transaction_type,"
                    + " external_amount,book_amount,currency,direction,difference_amount,"
                    + " exchange_rate_available,attribution,reason,status"
                    + " from finance_reconciliation_difference where task_id = ?"
                    + " order by currency, difference_type, id limit 20",
                id)
            .map(this::differenceRow);

    return new FinanceReconTaskSummary(
        task.get("external_line_count", Integer.class),
        task.get("matched_group_count", Integer.class),
        task.get("matched_line_count", Integer.class),
        task.get("difference_count", Integer.class),
        shapeCount.getOrDefault(ReconciliationMatcher.ONE_TO_ONE, 0L),
        shapeCount.getOrDefault(ReconciliationMatcher.ONE_TO_MANY, 0L),
        shapeCount.getOrDefault(ReconciliationMatcher.MANY_TO_ONE, 0L),
        shapeCount.getOrDefault(ReconciliationMatcher.MANY_TO_MANY, 0L),
        List.copyOf(summaries),
        List.copyOf(topDifferences),
        "🔴 **一次对账只有账户那一个币种可相加**（12.7.1.1 硬约束）。"
            + "上面的合计**按币种分组**呈现，**绝不做跨币种差额合计**；"
            + "汇率不可得时差额恒为 null（不按 1:1 兜底）。");
  }

  // ================================================================ 候选集

  /** 一条差异的草稿（落库前的中间形态）。 */
  record DifferenceDraft(
      String differenceType,
      String side,
      String bookSource,
      UUID groupKey,
      String externalKind,
      UUID externalLineId,
      UUID bookLineId,
      LocalDate occurredOn,
      String reference,
      String transactionType,
      String currency,
      String direction,
      BigDecimal externalAmount,
      BigDecimal bookAmount,
      BigDecimal differenceAmount,
      boolean exchangeRateAvailable,
      String attribution,
      String reason) {}

  /** 平台账单侧候选集（12.3.1.1 结算单头 + 12.3.1.2 结算明细行）。 */
  List<Item> externalItems(UUID shopId, LocalDate periodStart, LocalDate periodEnd) {
    var where = new StringBuilder(" where s.status not in ('VOID','SUPERSEDED')");
    var args = new ArrayList<Object>();
    if (shopId != null) {
      where.append(" and s.shop_id = ?");
      args.add(shopId);
    }
    where.append(" and s.period_start <= ?::date and s.period_end >= ?::date");
    args.add(periodEnd);
    args.add(periodStart);

    var items = new ArrayList<Item>();

    // ---- 结算单头（12.3.1.1）：平台结算单 = 平台账单的一笔总额 ----
    var headerRows =
        sql.fetch(
            "select s.id, s.settlement_no, s.arrived_at, s.period_end, s.settlement_amount,"
                + " s.currency from finance_settlement s" + where,
            args.toArray());
    for (var row : headerRows) {
      var amount = row.get("settlement_amount", BigDecimal.class);
      if (amount == null || amount.signum() <= 0) {
        continue;
      }
      var arrived = row.get("arrived_at", OffsetDateTime.class);
      var date =
          arrived == null
              ? row.get("period_end", LocalDate.class)
              : arrived.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
      items.add(
          new Item(
              "SETTLEMENT",
              row.get("id", UUID.class),
              row.get("settlement_no", String.class),
              row.get("settlement_no", String.class),
              date,
              "SETTLEMENT",
              "IN",
              amount,
              row.get("currency", String.class),
              "平台账单 · 结算单 " + row.get("settlement_no", String.class)));
    }

    // ---- 结算明细行（12.3.1.2）：平台交易明细 ----
    var lineRows =
        sql.fetch(
            "select l.id, s.settlement_no, l.order_reference, l.external_reference,"
                + " l.occurred_on, l.transaction_type, l.category, l.amount, l.currency"
                + " from finance_settlement_line l"
                + " join finance_settlement s on s.tenant_id = l.tenant_id and s.id = l.settlement_id"
                + where,
            args.toArray());
    for (var row : lineRows) {
      var amount = row.get("amount", BigDecimal.class);
      if (amount == null || amount.signum() == 0) {
        // 零金额明细不是资金事实 ⇒ 不进候选集（也不该被算成差异）。
        continue;
      }
      var reference =
          firstNonBlank(
              row.get("order_reference", String.class), row.get("external_reference", String.class));
      var rawType =
          firstNonBlank(
              row.get("transaction_type", String.class), row.get("category", String.class));
      items.add(
          new Item(
              "SETTLEMENT_LINE",
              row.get("id", UUID.class),
              row.get("settlement_no", String.class),
              reference,
              row.get("occurred_on", LocalDate.class),
              normalizeExternalType(rawType),
              amount.signum() > 0 ? "IN" : "OUT",
              amount.abs(),
              row.get("currency", String.class),
              "平台账单 · 结算单 "
                  + row.get("settlement_no", String.class)
                  + " · 明细行 "
                  + row.get("id", UUID.class)));
    }
    return items;
  }

  /** 我方账侧候选集（逐来源）。 */
  List<Item> bookItems(
      String source, ReconScope scope, List<BankStatementLine> bankLines, int windowDays) {
    var from = scope.periodStart().minusDays(windowDays);
    var to = scope.periodEnd().plusDays(windowDays);
    var items = new ArrayList<Item>();

    if ("RECEIVABLE".equals(source)) {
      var where = new StringBuilder(" where r.status in ('OPEN','PARTIALLY_SETTLED')");
      var args = new ArrayList<Object>();
      where.append(" and r.currency = ?");
      args.add(scope.currency());
      if (scope.shopId() != null) {
        // 店铺维度由**服务端**按授权范围过滤（不信任客户端传的范围参数）。
        where.append(" and (r.shop_id = ? or r.shop_id is null)");
        args.add(scope.shopId());
      }
      where.append(
          " and (r.confirmed_at at time zone 'UTC')::date between ?::date and ?::date");
      args.add(from);
      args.add(to);
      var rows =
          sql.fetch(
              "select l.id, r.receivable_no, l.order_no,"
                  + " l.source_reference as line_source_reference,"
                  + " r.source_reference as head_source_reference,"
                  + " r.confirmed_at, r.source, l.amount, r.currency"
                  + " from finance_receivable_line l"
                  + " join finance_receivable r on r.tenant_id = l.tenant_id"
                  + " and r.id = l.receivable_id" + where,
              args.toArray());
      for (var row : rows) {
        var amount = row.get("amount", BigDecimal.class);
        if (amount == null || amount.signum() <= 0) {
          continue;
        }
        var confirmed = row.get("confirmed_at", OffsetDateTime.class);
        items.add(
            new Item(
                "RECEIVABLE",
                row.get("id", UUID.class),
                row.get("receivable_no", String.class),
                firstNonBlank(
                    row.get("order_no", String.class),
                    row.get("line_source_reference", String.class),
                    row.get("head_source_reference", String.class)),
                confirmed == null
                    ? null
                    : confirmed.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
                normalizeBookType(row.get("source", String.class)),
                "IN",
                amount,
                row.get("currency", String.class),
                "我方账 · 应收单 "
                    + row.get("receivable_no", String.class)
                    + " · 明细行 "
                    + row.get("id", UUID.class)));
      }
      return items;
    }

    if ("PAYMENT_ORDER".equals(source)) {
      var where =
          new StringBuilder(
              " where o.status in ('PENDING_APPROVAL','APPROVED','EXECUTING','PAID')");
      var args = new ArrayList<Object>();
      where.append(" and l.currency = ?");
      args.add(scope.currency());
      where.append(" and (o.created_at at time zone 'UTC')::date between ?::date and ?::date");
      args.add(from);
      args.add(to);
      var rows =
          sql.fetch(
              "select l.id, o.order_no, pl.source_reference, o.created_at, l.amount, l.currency"
                  + " from finance_payment_order_line l"
                  + " join finance_payment_order o on o.tenant_id = l.tenant_id"
                  + " and o.id = l.order_id"
                  + " join finance_payable_line pl on pl.tenant_id = l.tenant_id"
                  + " and pl.id = l.payable_line_id" + where,
              args.toArray());
      for (var row : rows) {
        var amount = row.get("amount", BigDecimal.class);
        if (amount == null || amount.signum() <= 0) {
          continue;
        }
        var created = row.get("created_at", OffsetDateTime.class);
        items.add(
            new Item(
                "PAYMENT_ORDER",
                row.get("id", UUID.class),
                row.get("order_no", String.class),
                row.get("source_reference", String.class),
                created == null
                    ? null
                    : created.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
                "PAYMENT",
                "OUT",
                amount,
                row.get("currency", String.class),
                "我方账 · 付款单 "
                    + row.get("order_no", String.class)
                    + " · 明细行 "
                    + row.get("id", UUID.class)));
      }
      return items;
    }

    if ("BANK_STATEMENT".equals(source)) {
      // 🔴 银行流水**经授权只读 API**取得（12.7.1.2 的写入所有权，本模块不碰它的表）。
      for (var line : bankLines) {
        if (line.amount() == null || line.amount().signum() <= 0) {
          continue;
        }
        if (!scope.currency().equals(line.currency())) {
          continue;
        }
        if (line.occurredOn() != null
            && (line.occurredOn().isBefore(from) || line.occurredOn().isAfter(to))) {
          continue;
        }
        items.add(
            new Item(
                "BANK_STATEMENT",
                line.id(),
                line.bankReference(),
                line.externalReference(),
                line.occurredOn(),
                // 银行流水本身没有业务类型 ⇒ 空 ⇒ **永远不参与**维度计数。
                "",
                line.direction(),
                line.amount(),
                line.currency(),
                "我方账 · 银行流水 "
                    + line.bankReference()
                    + (line.counterparty() == null || line.counterparty().isBlank()
                        ? ""
                        : "（" + line.counterparty() + "）")));
      }
      return items;
    }

    return items;
  }

  // ================================================================ 落库

  /** 写一条匹配结果（一行 = 一个 (平台账单行, 我方账行) 对）。 */
  void insertMatch(
      UUID taskId,
      UUID tenantId,
      Group group,
      Pair pair,
      List<String> dimensions,
      UUID actorId,
      OffsetDateTime now) {
    var external = pair.external();
    var book = pair.book();
    insert(
        "finance_reconciliation_match",
        List.of(
            column("id", UUID.randomUUID()),
            column("tenant_id", tenantId),
            column("task_id", taskId),
            column("group_key", group.groupKey()),
            column("group_no", group.groupNo()),
            column("shape", group.shape()),
            column("book_source", book.kind()),
            column("external_kind", external.kind()),
            column("external_line_id", external.id()),
            column("external_no", external.no()),
            column("external_reference", external.reference()),
            column("external_type", external.type()),
            column("external_occurred_on", external.date(), "::date"),
            column("external_amount", external.amount()),
            column("book_line_id", book.id()),
            column("book_no", book.no()),
            column("book_reference", book.reference()),
            column("book_type", book.type()),
            column("book_occurred_on", book.date(), "::date"),
            column("book_amount", book.amount()),
            column("currency", external.currency()),
            column("direction", external.direction()),
            column("group_external_amount", group.externalAmount()),
            column("group_book_amount", group.bookAmount()),
            column("group_amount_difference", group.difference()),
            column("matched_dimensions", pgTextArray(dimensions), "::text[]"),
            column("dimension_count", dimensions.size()),
            column("match_basis", ReconciliationMatcher.basisOf(pair)),
            column("day_difference", pair.dayDifference()),
            column("matched_by", actorId),
            column("matched_at", now, "::timestamptz"),
            column("created_at", now, "::timestamptz")));
  }

  /** 写一条差异。 */
  void insertDifference(UUID taskId, UUID tenantId, DifferenceDraft draft, OffsetDateTime now) {
    insert(
        "finance_reconciliation_difference",
        List.of(
            column("id", UUID.randomUUID()),
            column("tenant_id", tenantId),
            column("task_id", taskId),
            column("difference_type", draft.differenceType()),
            column("side", draft.side()),
            column("book_source", draft.bookSource()),
            column("group_key", draft.groupKey()),
            column("external_kind", draft.externalKind()),
            column("external_line_id", draft.externalLineId()),
            column("book_line_id", draft.bookLineId()),
            column("occurred_on", draft.occurredOn(), "::date"),
            column("reference", draft.reference()),
            column("transaction_type", draft.transactionType()),
            column("external_amount", draft.externalAmount()),
            column("book_amount", draft.bookAmount()),
            column("currency", currencyOf(draft)),
            column("direction", directionOf(draft)),
            column("difference_amount", draft.differenceAmount()),
            column("exchange_rate_available", draft.exchangeRateAvailable()),
            column("attribution", draft.attribution()),
            column("reason", draft.reason()),
            column("status", "OPEN"),
            column("created_at", now, "::timestamptz")));
  }

  /** 追加一条事件（append-only 举证）。 */
  void appendEvent(
      UUID tenantId,
      UUID taskId,
      String eventType,
      String fromStatus,
      String toStatus,
      String summary,
      Map<String, Object> detail,
      UUID actorId,
      OffsetDateTime now) {
    insert(
        "finance_reconciliation_event",
        List.of(
            column("id", UUID.randomUUID()),
            column("tenant_id", tenantId),
            column("task_id", taskId),
            column("event_type", eventType),
            column("from_status", fromStatus),
            column("to_status", toStatus),
            column("summary", summary),
            column("detail", toJson(detail), "::jsonb"),
            column("actor_id", actorId),
            column("occurred_at", now, "::timestamptz"),
            column("created_at", now, "::timestamptz")));
  }

  // ================================================================ 行映射

  FinanceReconTaskRow taskRow(Record r) {
    return new FinanceReconTaskRow(
        r.get("id", UUID.class),
        r.get("task_no", String.class),
        r.get("account_id", UUID.class),
        r.get("account_no", String.class),
        r.get("account_name", String.class),
        r.get("currency", String.class),
        r.get("legal_entity_name", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_name", String.class),
        r.get("platform_code", String.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        stringList(r.get("book_sources", String[].class)),
        r.get("date_window_days", Integer.class),
        r.get("amount_tolerance", BigDecimal.class),
        r.get("status", String.class),
        FinanceReconTaskStatus.valueOf(r.get("status", String.class)).label(),
        r.get("external_line_count", Integer.class),
        r.get("matched_group_count", Integer.class),
        r.get("matched_line_count", Integer.class),
        r.get("difference_count", Integer.class),
        r.get("matched_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("version", Integer.class) == null ? 0L : r.get("version", Integer.class).longValue(),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  FinanceReconMatchRow matchRow(Record r) {
    var shape = r.get("shape", String.class);
    var bookSource = r.get("book_source", String.class);
    var externalKind = r.get("external_kind", String.class);
    return new FinanceReconMatchRow(
        r.get("id", UUID.class),
        r.get("task_id", UUID.class),
        r.get("group_key", UUID.class),
        r.get("group_no", Integer.class),
        shape,
        FinanceReconMatchShape.valueOf(shape).label(),
        bookSource,
        FinanceReconBookSource.valueOf(bookSource).label(),
        externalKind,
        externalKindLabel(externalKind),
        r.get("external_line_id", UUID.class),
        r.get("external_no", String.class),
        r.get("external_reference", String.class),
        r.get("external_type", String.class),
        r.get("external_occurred_on", LocalDate.class),
        r.get("external_amount", BigDecimal.class),
        r.get("book_line_id", UUID.class),
        r.get("book_no", String.class),
        r.get("book_reference", String.class),
        r.get("book_type", String.class),
        r.get("book_occurred_on", LocalDate.class),
        r.get("book_amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("direction", String.class),
        r.get("group_external_amount", BigDecimal.class),
        r.get("group_book_amount", BigDecimal.class),
        r.get("group_amount_difference", BigDecimal.class),
        stringList(r.get("matched_dimensions", String[].class)),
        r.get("dimension_count", Integer.class),
        r.get("match_basis", String.class),
        r.get("day_difference", Integer.class),
        r.get("matched_at", OffsetDateTime.class));
  }

  FinanceReconDifferenceRow differenceRow(Record r) {
    var type = r.get("difference_type", String.class);
    var side = r.get("side", String.class);
    var bookSource = r.get("book_source", String.class);
    var status = r.get("status", String.class);
    return new FinanceReconDifferenceRow(
        r.get("id", UUID.class),
        r.get("task_id", UUID.class),
        type,
        FinanceReconDifferenceType.valueOf(type).label(),
        side,
        sideLabel(side),
        bookSource,
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
        differenceStatusLabel(status));
  }

  FinanceReconEventRow eventRow(Record r) {
    var eventType = r.get("event_type", String.class);
    return new FinanceReconEventRow(
        r.get("id", UUID.class),
        r.get("task_id", UUID.class),
        eventType,
        eventLabel(eventType),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("summary", String.class),
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
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

  /** 任务的行（不存在 ⇒ 404）。 */
  Record requireTask(UUID id) {
    var row = sql.fetchOne("select * from finance_reconciliation_task where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对账任务不存在：" + id);
    }
    return row;
  }

  /** 对账范围的解析结果。 */
  record Scope(
      UUID accountId,
      String accountNo,
      String accountName,
      String currency,
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      UUID shopId,
      String shopCode,
      String shopName,
      String platformCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      List<String> bookSources,
      int dateWindowDays,
      BigDecimal amountTolerance) {}

  /**
   * 解析并校验对账范围。
   *
   * <p>⚠️ <b>服务端按授权范围过滤</b>：账户从本租户的账户档案里取（RLS 已隔离），
   * 店铺必须**在调用方的授权店铺列表里**（走 2 渠道与店铺的只读端口）——
   * **不信任客户端传的范围参数**。
   */
  private Scope resolveScope(
      UUID accountId,
      UUID shopId,
      LocalDate periodStart,
      LocalDate periodEnd,
      List<String> bookSources,
      Integer dateWindowDays,
      BigDecimal amountTolerance,
      String bearer) {
    // ---- 账户：必须存在、启用中（停用账户不再对账）----
    var account =
        sql.fetchOne(
            "select id,account_no,account_name,account_type,currency,legal_entity_id,"
                + " legal_entity_code,legal_entity_name,status from finance_fund_account"
                + " where id = ?",
            accountId);
    if (account == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资金账户不存在：" + accountId);
    }
    if (!"ACTIVE".equals(account.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "资金账户已停用，不能对账：" + account.get("account_no", String.class));
    }

    // ---- 周期：结束 ≥ 开始 ----
    if (periodEnd.isBefore(periodStart)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "对账周期的结束日不能早于开始日");
    }

    // ---- 我方账来源：受控词表的非空子集 ----
    var sources = new ArrayList<String>();
    if (bookSources != null) {
      for (var raw : bookSources) {
        if (raw == null || raw.isBlank()) {
          continue;
        }
        var value = raw.trim().toUpperCase(Locale.ROOT);
        if (!BOOK_SOURCES.contains(value)) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "未知的我方账来源："
                  + raw
                  + "（可用："
                  + String.join("、", BOOK_SOURCES)
                  + "）");
        }
        if (!sources.contains(value)) {
          sources.add(value);
        }
      }
    }
    if (sources.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "至少要选一个我方账来源（应收 / 付款单 / 银行流水）");
    }

    // ---- 匹配参数：代码内受控词表 + 入参（**不是规则管理表**）----
    var windowDays = dateWindowDays == null ? 7 : dateWindowDays;
    if (windowDays < 1 || windowDays > 31) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "日期窗口必须在 1 ~ 31 天之间（放到几百天等于用「金额相同」冒充匹配）");
    }
    var tolerance = amountTolerance == null ? BigDecimal.ZERO : money(amountTolerance);
    if (tolerance.signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额容差不能为负");
    }

    // ---- 店铺：**服务端**按授权范围过滤 ----
    var shopCode = "";
    var shopName = "";
    var platformCode = "";
    if (shopId != null) {
      var shop = authorizedShop(shopId, bearer);
      if (shop == null) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "店铺不在当前账号的授权范围内：" + shopId);
      }
      shopCode = shop.code();
      shopName = shop.name();
      platformCode = shop.platformCode();
    }

    return new Scope(
        account.get("id", UUID.class),
        account.get("account_no", String.class),
        account.get("account_name", String.class),
        account.get("currency", String.class),
        account.get("legal_entity_id", UUID.class),
        account.get("legal_entity_code", String.class),
        account.get("legal_entity_name", String.class),
        shopId,
        shopCode,
        shopName,
        platformCode,
        periodStart,
        periodEnd,
        List.copyOf(sources),
        windowDays,
        tolerance);
  }

  /** 上游授权的店铺（2 渠道与店铺）。 */
  record ShopView(String code, String name, String platformCode) {}

  private ShopView authorizedShop(UUID shopId, String bearer) {
    for (var shop : shopPort.shops(bearer)) {
      if (shop.id().equals(shopId)) {
        return new ShopView(shop.code(), shop.name(), shop.platformCode());
      }
    }
    return null;
  }

  /** 任务号：RT-日期-8 位十六进制（租户内唯一、可读）。 */
  String taskNo() {
    return TASK_NO_PREFIX
        + nowUtc().toLocalDate().toString().replace("-", "")
        + "-"
        + UUID.randomUUID().toString().substring(0, 8);
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

  static String firstNonBlank(String... values) {
    for (var value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return "";
  }

  static List<String> stringList(String[] values) {
    return values == null ? List.of() : List.of(values);
  }

  /** PG 数组字面量（配合 {@code ?::text[]} 绑定）。 */
  static String pgTextArray(List<String> values) {
    if (values == null || values.isEmpty()) {
      return "{}";
    }
    var sb = new StringBuilder("{");
    for (var i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append("\"").append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
    }
    return sb.append("}").toString();
  }

  /** 差异的币种（平台侧优先，其次我方侧）。 */
  static String currencyOf(DifferenceDraft draft) {
    return draft.currency();
  }

  /** 差异的方向。 */
  static String directionOf(DifferenceDraft draft) {
    return draft.direction();
  }

  /** 平台账单的归一交易类型。 */
  static String normalizeExternalType(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    return switch (raw.trim().toUpperCase(Locale.ROOT)) {
      case "ORDER_SALES", "SALES" -> "SALE";
      case "REFUND" -> "REFUND";
      case "COMMISSION", "FBA_FEE", "STORAGE_FEE", "SHIPPING_FEE", "ADVERTISING" -> "FEE";
      case "COMPENSATION" -> "COMPENSATION";
      case "ADJUSTMENT" -> "ADJUSTMENT";
      case "WITHHOLDING_TAX" -> "TAX";
      default -> "";
    };
  }

  /** 我方账的归一交易类型。 */
  static String normalizeBookType(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    return switch (raw.trim().toUpperCase(Locale.ROOT)) {
      case "PLATFORM_SETTLEMENT", "PLATFORM_WITHHOLDING", "B2B_CREDIT_TERM" -> "SALE";
      default -> "";
    };
  }

  static String externalKindLabel(String kind) {
    return switch (kind) {
      case "SETTLEMENT" -> "结算单（12.3.1.1）";
      case "SETTLEMENT_LINE" -> "结算明细行（12.3.1.2）";
      default -> kind;
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

  static String differenceStatusLabel(String status) {
    return switch (status) {
      case "OPEN" -> "待处理（12.3.2.2 未交付）";
      case "CLAIMED" -> "已认领";
      case "ADJUSTED" -> "已调整";
      case "WRITTEN_OFF" -> "已核销";
      default -> status;
    };
  }

  static String eventLabel(String eventType) {
    return switch (eventType) {
      case "TASK_CREATED" -> "建立对账任务";
      case "TASK_SCOPE_UPDATED" -> "改动对账范围";
      case "MATCH_COMPLETED" -> "完成自动匹配";
      default -> eventType;
    };
  }

  static String attributionOf(Group group) {
    var external = group.pairs().get(0).external();
    var book = group.pairs().get(0).book();
    return external.attribution() + " ⇄ " + book.attribution();
  }

  static String attributionOf(NearMiss nearMiss) {
    return nearMiss.external().attribution() + " ⇄ " + nearMiss.book().attribution();
  }

  static BigDecimal sum(BigDecimal a, BigDecimal b) {
    if (a == null && b == null) {
      return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.add(b);
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
