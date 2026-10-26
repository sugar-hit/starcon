package com.starcon.finance;

import static com.starcon.finance.ReconciliationMatchRuleModels.*;

import com.starcon.finance.ReconciliationMatcher.Spec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 12.3.2.3 自动匹配规则。
 *
 * <p>蓝图 12-finance.md：「配置对账自动匹配规则：匹配键（订单号 + 金额 + 日期 + 类型）、
 * 容差（汇率差、手续费）、优先级、模糊匹配兜底，配置匹配率目标与监控。」输入：「规则配置；
 * 历史对账结果用于规则调优。」输出：「规则集 + 匹配率统计 + 未匹配原因分布。」关系：
 * 「上游 = 12.3.2.1；下游 = 匹配引擎。<b>匹配率是财务自动化的核心指标</b>，
 * 持续低于目标时应排查上游数据质量而非调规则。」
 *
 * <p><b>本模块的四张表</b>（{@code V375__reconciliation_match_rule.sql}）：
 * 规则集 / 规则事件流 / 匹配率快照 / 未匹配原因分布。
 *
 * <p><b>🔴 卷 12 硬约束的落点</b>：蓝图把匹配键写成「订单号 + 金额 + 日期 + 类型」**四项**，
 * 但加粗硬约束是「金额相同只是候选，必须结合订单号、日期、类型」——
 * ⇒ 本模块把**金额从匹配键里剔出去**：
 *
 * <ul>
 *   <li>{@code match_keys} 只能是 {REFERENCE, DATE, TYPE}，<b>至少两个</b>（DB CHECK + 触发器）；</li>
 *   <li>{@code min_dimension_count}（至少对上几个维度才算候选）<b>≥ 2</b>；</li>
 *   <li>金额只以<b>容差</b>出现（金额容差 / 手续费容差 / 汇率差容差），
 *       作用在候选配对带与残差归因上，<b>不计入维度个数</b>。</li>
 * </ul>
 *
 * <p><b>🔴 上游 12.3.2.1 的表一律不改</b>：{@code finance_reconciliation_task} /
 * {@code finance_reconciliation_match} / {@code finance_reconciliation_difference} /
 * {@code finance_reconciliation_event} 是 12.3.2.1 的写入所有权 ⇒ 本模块**零 ALTER**、
 * 零 DROP、零新触发器挂上游表。本模块**只读**它们（匹配率统计与未匹配原因分布
 * 都从它们的真实结果行算出来）—— 同服务同库的合法读路径。
 *
 * <p><b>🔴 匹配率按币种分组，绝不跨币种合计</b>：12.7.1.1 硬约束「账户单一币种」，
 * 本模块也不折算汇率 ⇒ 快照与监控**按币种**出。
 */
@Service
public class ReconciliationMatchRuleService {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ReconciliationMatchRuleService.class);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 规则号前缀。 */
  static final String RULE_NO_PREFIX = "MR-";

  /** 匹配键词表（🔴 **金额不在里面**）。 */
  static final List<String> MATCH_KEYS =
      List.of(
          ReconciliationMatcher.DIM_REFERENCE,
          ReconciliationMatcher.DIM_DATE,
          ReconciliationMatcher.DIM_TYPE);

  private final DSLContext sql;

  public ReconciliationMatchRuleService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public FinanceMatchRuleCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceMatchRuleCapability>();
    var gaps = new ArrayList<FinanceMatchRuleGap>();

    capabilities.add(
        new FinanceMatchRuleCapability(
            "12.3.2.3",
            "匹配键（订单号 + 日期 + 类型）",
            true,
            "finance_reconciliation_match_rule.match_keys + min_dimension_count",
            "",
            "🔴 蓝图把匹配键写成「订单号 + 金额 + 日期 + 类型」，但卷 12 的加粗硬约束是"
                + "「金额相同只是候选，必须结合订单号、日期、类型」⇒ **金额从匹配键里剔出去了**："
                + "匹配键只能取 {订单号, 日期, 类型} 且**至少两个**（DB CHECK + 触发器双保险），"
                + "至少对上的维度数**≥ 2**。⇒ 「只按金额」「金额 + 单个维度」这两类开关"
                + "**在结构上写不出来**（绕过服务层直连 SQL 也一样）。"
                + "金额只以**容差**出现，**不计入维度个数**。"));

    capabilities.add(
        new FinanceMatchRuleCapability(
            "12.3.2.3",
            "容差（金额 / 手续费 / 汇率差）",
            true,
            "amount_tolerance / fee_tolerance / exchange_rate_tolerance",
            "",
            "三种容差各有各的口径：**金额容差** = 候选配对带（|Δ| ≤ 它 ⇒ 可配对）；"
                + "**手续费容差** = 残差归因带（平台扣佣金 ⇒ 我方账比平台多，多出来的落在这个带内"
                + "⇒ 归因为「手续费差异」而不是笼统的「金额对不齐」）；"
                + "**汇率差容差** = 同币种下两侧折算汇率不同留下的残差带。"
                + "⚠️ 容差**不放宽「配平」**：12.3.2.1 的 DB 触发器要求组内两侧合计逐位相等"
                + "⇒ 本模块**不去 ALTER 那个触发器**（那是上游的写入所有权）。"
                + "🔴 汇率差容差**不是跨币种折算开关**：跨币种差额恒为 null（汇率不可得）。"));

    capabilities.add(
        new FinanceMatchRuleCapability(
            "12.3.2.3",
            "优先级",
            true,
            "finance_reconciliation_match_rule.priority（激活态 partial UNIQUE index）",
            "",
            "数字**越小越先试**。🔴 激活中的规则在**同一租户内优先级唯一**"
                + "（DB partial UNIQUE index）⇒ 「哪条规则先试」是**确定的**，"
                + "不依赖 id / created_at 的偶然次序。规则自己声明适用范围"
                + "（币种 / 账户 / 店铺，可空 = 全部），引擎按任务口径挑规则 ⇒ "
                + "**上游任务表不用加 rule_id 列**（不改 12.3.2.1 的表）。"));

    capabilities.add(
        new FinanceMatchRuleCapability(
            "12.3.2.3",
            "模糊匹配兜底",
            true,
            "fuzzy_* 列 + ReconciliationMatcher 的模糊阶段",
            "",
            "兜底**放松「怎么比」，不放松「要比几个」**：模糊阶段把**订单号**改成"
                + "**前缀**比（前缀至少 4 个字符 —— 太短形同没有单号）、日期窗口放宽、"
                + "残差带放宽，但**仍要求对上 ≥ 2 个维度**（{订单号, 日期, 类型}）"
                + "⇒ 兜底**不会**退化成「金额相同即匹配」。"));

    capabilities.add(
        new FinanceMatchRuleCapability(
            "12.3.2.3",
            "匹配率目标与监控",
            true,
            "finance_reconciliation_match_rate + finance_reconciliation_match_rate_reason",
            "",
            "匹配率 = 匹配行数 ÷（匹配行数 + 未匹配条数），**全部来自 12.3.2.1 的真实结果行**。"
                + "🔴 分母为 0 时比率恒为 **null**（**不写 0** —— 0 会被读成「一条都没配上」）。"
                + "未匹配原因分布**扩充**了 12.3.2.1 的五种差异类型："
                + "残差落在手续费 / 汇率差容差带内的**另归一类** ⇒ 运营能一眼看出"
                + "「这批没对上的主要是手续费，不是账错了」。"
                + "🔴 **按币种分组，绝不跨币种合计**（12.7.1.1 账户单一币种）。"
                + "蓝图原话：「持续低于目标时应**排查上游数据质量**而非调规则」"
                + "⇒ 低于目标时页面明写这条处置口径。"));

    capabilities.add(
        new FinanceMatchRuleCapability(
            "12.3.2.1",
            "对账任务（上游）",
            true,
            "finance_reconciliation_task + finance_reconciliation_match"
                + " + finance_reconciliation_difference",
            "",
            "上游已交付。本模块**只读**它的匹配结果与差异清单（匹配率统计的**唯一数据来源**），"
                + "**零 ALTER / 零 DROP / 零新触发器挂上游表 / 零改上游 GRANT**。"
                + "12.3.2.1 的匹配引擎（ReconciliationMatcher）已改成**规则驱动**："
                + "有启用中的规则时按规则跑，没有时退回原来的内置口径（行为逐位不变）。"));

    gaps.add(
        new FinanceMatchRuleGap(
            "12.3.2.2", "差异处理", "finance", false, "12.3 结算与对账",
            "下游未交付：它接本模块的未匹配原因分布做差异归类与处理闭环。"
                + "本模块**只统计**原因分布，**不处理**差异。"));

    gaps.add(
        new FinanceMatchRuleGap(
            "12.7.2.1", "汇率敞口", "finance", false, "12.7 资金与银行",
            "汇率差容差只是**同币种**下两侧折算口径不同的残差带；"
                + "真正的跨币种折算与汇率敞口归 12.7.2.1 ⇒ 本模块**不折算**、"
                + "跨币种差额恒为 null（汇率不可得，不按 1:1 兜底）。"));

    return new FinanceMatchRuleCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：词表 + **真实数据**（资金账户取自 12.7.1.1）。 */
  public FinanceMatchRuleOptions options() {
    var accounts =
        sql.fetch(
                "select id,account_no,account_name,currency,legal_entity_name"
                    + " from finance_fund_account where status = 'ACTIVE' order by account_no")
            .map(
                r ->
                    new FinanceMatchRuleAccount(
                        r.get("id", UUID.class),
                        r.get("account_no", String.class),
                        r.get("account_name", String.class),
                        r.get("currency", String.class),
                        r.get("legal_entity_name", String.class)));

    var currencies =
        sql.fetch("select distinct currency from finance_fund_account order by currency")
            .map(r -> r.get(0, String.class));

    var statuses = new ArrayList<FinanceMatchRuleOption>();
    for (var status : FinanceMatchRuleStatus.values()) {
      statuses.add(new FinanceMatchRuleOption(status.name(), status.label()));
    }

    var keys = new ArrayList<FinanceMatchRuleOption>();
    keys.add(new FinanceMatchRuleOption(ReconciliationMatcher.DIM_REFERENCE, "订单号 / 对方单号"));
    keys.add(new FinanceMatchRuleOption(ReconciliationMatcher.DIM_DATE, "日期（窗口内）"));
    keys.add(new FinanceMatchRuleOption(ReconciliationMatcher.DIM_TYPE, "交易类型"));

    var reasons = new ArrayList<FinanceMatchRuleOption>();
    for (var reason : FinanceMatchRuleReason.values()) {
      reasons.add(new FinanceMatchRuleOption(reason.name(), reason.label()));
    }

    return new FinanceMatchRuleOptions(
        List.copyOf(statuses),
        List.copyOf(keys),
        List.copyOf(reasons),
        currencies,
        accounts,
        "🔴 **匹配键里没有「金额」** —— 卷 12 硬约束「金额相同只是候选，必须结合订单号、日期、类型」。"
            + "匹配键至少两个、至少对上的维度数至少 2 ⇒ 「金额 + 单个维度」配不成对。"
            + "账户候选取自 **12.7.1.1 的真实账户档案**（不是常量）。");
  }

  // ================================================================ 规则 CRUD

  /** 规则列表（真分页 + 真筛选 + 真搜索）。 */
  public FinanceMatchRulePage listRules(String status, String keyword, int page, int size) {
    var pageNo = Math.max(0, page);
    var pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    var where = new StringBuilder(" where 1=1");
    var args = new ArrayList<Object>();

    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      args.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      var kw = keyword.trim().toLowerCase(Locale.ROOT);
      where.append(
          " and (position(? in lower(rule_no)) > 0"
              + " or position(? in lower(rule_name)) > 0"
              + " or position(? in lower(note)) > 0)");
      args.add(kw);
      args.add(kw);
      args.add(kw);
    }

    var total =
        sql.fetchOne("select count(*) from finance_reconciliation_match_rule" + where, args.toArray())
            .get(0, Long.class);

    var bindings = new ArrayList<Object>(args);
    bindings.add(pageSize);
    bindings.add(pageNo * pageSize);
    var rows =
        sql.fetch(
            "select id,rule_no,rule_name,status,priority,match_keys,min_dimension_count,"
                + " date_window_days,amount_tolerance,fee_tolerance,exchange_rate_tolerance,"
                + " fuzzy_enabled,fuzzy_reference_prefix_length,fuzzy_date_window_days,"
                + " fuzzy_amount_tolerance,match_rate_target,currency,account_id,shop_id,note,"
                + " version,activated_at,created_at,updated_at from finance_reconciliation_match_rule"
                + where
                + " order by priority asc, rule_no asc limit ? offset ?",
            bindings.toArray());

    return new FinanceMatchRulePage(rows.map(this::ruleRow), pageNo, pageSize, total);
  }

  /** 规则详情。 */
  public FinanceMatchRuleRow detail(UUID id) {
    return ruleRow(requireRule(id));
  }

  /** 建规则。 */
  @Transactional
  public FinanceMatchRuleRow createRule(FinanceMatchRuleInput input, AccessService.Access actor) {
    var spec = resolveSpec(input);
    var id = UUID.randomUUID();
    var now = nowUtc();

    insert(
        "finance_reconciliation_match_rule",
        List.of(
            column("id", id),
            column("tenant_id", actor.tenantId()),
            column("rule_no", ruleNo()),
            column("rule_name", trim(input.ruleName())),
            column("status", "DRAFT"),
            column("priority", spec.priority()),
            column("match_keys", pgTextArray(spec.matchKeys()), "::text[]"),
            column("min_dimension_count", spec.minDimensionCount()),
            column("date_window_days", spec.dateWindowDays()),
            column("amount_tolerance", spec.amountTolerance()),
            column("fee_tolerance", spec.feeTolerance()),
            column("exchange_rate_tolerance", spec.exchangeRateTolerance()),
            column("fuzzy_enabled", spec.fuzzyEnabled()),
            column("fuzzy_reference_prefix_length", spec.fuzzyReferencePrefixLength()),
            column("fuzzy_date_window_days", spec.fuzzyDateWindowDays()),
            column("fuzzy_amount_tolerance", spec.fuzzyAmountTolerance()),
            column("match_rate_target", spec.matchRateTarget()),
            column("currency", spec.currency()),
            column("account_id", spec.accountId()),
            column("shop_id", spec.shopId()),
            column("note", trim(input.note())),
            column("version", 0),
            column("created_by", actor.userId()),
            column("created_at", now, "::timestamptz"),
            column("updated_at", now, "::timestamptz")));

    appendEvent(
        actor.tenantId(),
        id,
        "RULE_CREATED",
        "DRAFT",
        "DRAFT",
        "建立匹配规则 " + trim(input.ruleName()) + "（匹配键：" + String.join("、", spec.matchKeys())
            + "，至少对上 " + spec.minDimensionCount() + " 个维度）",
        Map.of(
            "matchKeys", spec.matchKeys(),
            "minDimensionCount", spec.minDimensionCount(),
            "priority", spec.priority()),
        actor.userId(),
        now);

    return ruleRow(requireRule(id));
  }

  /** 改规则（口径可改；激活中的规则改动**立即对新匹配生效**）。 */
  @Transactional
  public FinanceMatchRuleRow updateRule(
      UUID id, FinanceMatchRuleInput input, AccessService.Access actor) {
    var rule = requireRule(id);
    var spec = resolveSpec(input);
    var now = nowUtc();

    sql.execute(
        "update finance_reconciliation_match_rule set rule_name = ?, priority = ?,"
            + " match_keys = ?::text[], min_dimension_count = ?, date_window_days = ?,"
            + " amount_tolerance = ?, fee_tolerance = ?, exchange_rate_tolerance = ?,"
            + " fuzzy_enabled = ?, fuzzy_reference_prefix_length = ?, fuzzy_date_window_days = ?,"
            + " fuzzy_amount_tolerance = ?, match_rate_target = ?, currency = ?, account_id = ?,"
            + " shop_id = ?, note = ?, version = version + 1, updated_at = ?::timestamptz"
            + " where id = ?",
        trim(input.ruleName()),
        spec.priority(),
        pgTextArray(spec.matchKeys()),
        spec.minDimensionCount(),
        spec.dateWindowDays(),
        spec.amountTolerance(),
        spec.feeTolerance(),
        spec.exchangeRateTolerance(),
        spec.fuzzyEnabled(),
        spec.fuzzyReferencePrefixLength(),
        spec.fuzzyDateWindowDays(),
        spec.fuzzyAmountTolerance(),
        spec.matchRateTarget(),
        spec.currency(),
        spec.accountId(),
        spec.shopId(),
        trim(input.note()),
        now,
        id);

    appendEvent(
        actor.tenantId(),
        id,
        "RULE_UPDATED",
        rule.get("status", String.class),
        rule.get("status", String.class),
        "改动匹配规则 " + trim(input.ruleName()) + "（匹配键：" + String.join("、", spec.matchKeys())
            + "，至少对上 " + spec.minDimensionCount() + " 个维度）",
        Map.of(
            "matchKeys", spec.matchKeys(),
            "minDimensionCount", spec.minDimensionCount(),
            "priority", spec.priority()),
        actor.userId(),
        now);

    return ruleRow(requireRule(id));
  }

  /** 启用规则（同一租户内激活态的优先级必须唯一）。 */
  @Transactional
  public FinanceMatchRuleRow activateRule(UUID id, AccessService.Access actor) {
    var rule = requireRule(id);
    var status = rule.get("status", String.class);
    if ("ACTIVE".equals(status)) {
      return ruleRow(rule);
    }
    var now = nowUtc();
    try {
      sql.execute(
          "update finance_reconciliation_match_rule set status = 'ACTIVE', activated_at = ?::timestamptz,"
              + " version = version + 1, updated_at = ?::timestamptz where id = ?",
          now,
          now,
          id);
    } catch (org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "启用失败：同一租户内**激活中的规则优先级必须唯一**（当前优先级 "
              + rule.get("priority", Integer.class)
              + " 已被另一条规则占用）");
    }

    appendEvent(
        actor.tenantId(),
        id,
        "RULE_ACTIVATED",
        status,
        "ACTIVE",
        "启用匹配规则 " + rule.get("rule_name", String.class)
            + "（优先级 " + rule.get("priority", Integer.class) + "）",
        Map.of("priority", rule.get("priority", Integer.class)),
        actor.userId(),
        now);

    return ruleRow(requireRule(id));
  }

  /** 停用规则。 */
  @Transactional
  public FinanceMatchRuleRow deactivateRule(UUID id, AccessService.Access actor) {
    var rule = requireRule(id);
    var status = rule.get("status", String.class);
    if ("DISABLED".equals(status)) {
      return ruleRow(rule);
    }
    var now = nowUtc();
    sql.execute(
        "update finance_reconciliation_match_rule set status = 'DISABLED',"
            + " version = version + 1, updated_at = ?::timestamptz where id = ?",
        now,
        id);

    appendEvent(
        actor.tenantId(),
        id,
        "RULE_DISABLED",
        status,
        "DISABLED",
        "停用匹配规则 " + rule.get("rule_name", String.class),
        Map.of(),
        actor.userId(),
        now);

    return ruleRow(requireRule(id));
  }

  /** 规则事件流（真分页；append-only 举证）。 */
  public FinanceMatchRuleEventPage events(UUID id, int page, int size) {
    requireRule(id);
    var pageNo = Math.max(0, page);
    var pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    var total =
        sql.fetchOne(
                "select count(*) from finance_reconciliation_match_rule_event where rule_id = ?", id)
            .get(0, Long.class);

    var rows =
        sql.fetch(
            "select id,rule_id,event_type,from_status,to_status,summary,actor_id,occurred_at"
                + " from finance_reconciliation_match_rule_event where rule_id = ?"
                + " order by occurred_at desc, id desc limit ? offset ?",
            id,
            pageSize,
            pageNo * pageSize);

    return new FinanceMatchRuleEventPage(rows.map(this::eventRow), pageNo, pageSize, total);
  }

  // ================================================================ 匹配引擎接口

  /**
   * **启用中的规则集**（引擎侧视图），按优先级升序。
   *
   * <p>适用范围：规则的币种 / 账户 / 店铺为空 = 全部适用。
   *
   * @param currency 任务口径的币种
   * @param accountId 任务口径的账户
   * @param shopId 任务口径的店铺（空 = 全部店铺 ⇒ 只有不限店铺的规则适用）
   */
  public List<Spec> activeSpecs(String currency, UUID accountId, UUID shopId) {
    var rows =
        sql.fetch(
            "select id,priority,match_keys,min_dimension_count,date_window_days,amount_tolerance,"
                + " fuzzy_enabled,fuzzy_reference_prefix_length,fuzzy_date_window_days,"
                + " fuzzy_amount_tolerance from finance_reconciliation_match_rule"
                + " where status = 'ACTIVE'"
                + " and (currency is null or currency = ?)"
                + " and (account_id is null or account_id = ?)"
                + " and (shop_id is null or shop_id = ?)"
                + " order by priority asc, rule_no asc",
            currency,
            accountId,
            shopId);

    var specs = new ArrayList<Spec>();
    for (var row : rows) {
      specs.add(
          new Spec(
              row.get("id", UUID.class).toString(),
              row.get("priority", Integer.class),
              stringList(row.get("match_keys", String[].class)),
              row.get("min_dimension_count", Integer.class),
              row.get("date_window_days", Integer.class),
              row.get("amount_tolerance", BigDecimal.class),
              row.get("fuzzy_enabled", Boolean.class),
              row.get("fuzzy_reference_prefix_length", Integer.class),
              row.get("fuzzy_date_window_days", Integer.class),
              row.get("fuzzy_amount_tolerance", BigDecimal.class)));
    }
    return List.copyOf(specs);
  }

  // ================================================================ 匹配率监控

  /**
   * 匹配率监控视图。
   *
   * @param taskId 对账任务（12.3.2.1）。**空 = 租户级汇总**。
   */
  public FinanceMatchRateMonitoring monitoring(UUID taskId) {
    String taskNo = "";
    String currency = null;
    UUID accountId = null;
    UUID shopId = null;

    if (taskId != null) {
      var task =
          sql.fetchOne(
              "select task_no,currency,account_id,shop_id,status from finance_reconciliation_task"
                  + " where id = ?",
              taskId);
      if (task == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对账任务不存在：" + taskId);
      }
      taskNo = task.get("task_no", String.class);
      currency = task.get("currency", String.class);
      accountId = task.get("account_id", UUID.class);
      shopId = task.get("shop_id", UUID.class);
    }

    // ---- 口径规则（提供匹配率目标的那条）：启用中、适用范围命中、优先级最高 ----
    var rule =
        sql.fetchOne(
            "select id,rule_no,priority,match_rate_target,fee_tolerance,exchange_rate_tolerance"
                + " from finance_reconciliation_match_rule where status = 'ACTIVE'"
                + " and (currency is null or currency = ?)"
                + " and (account_id is null or account_id = ?)"
                + " and (shop_id is null or shop_id = ?)"
                + " order by priority asc, rule_no asc limit 1",
            currency,
            accountId,
            shopId);

    var feeTolerance =
        rule == null
            ? BigDecimal.ZERO
            : rule.get("fee_tolerance", BigDecimal.class);
    var exchangeTolerance =
        rule == null ? BigDecimal.ZERO : rule.get("exchange_rate_tolerance", BigDecimal.class);

    // ---- 计数（全部来自 12.3.2.1 的真实结果行）----
    var matchedByCurrency = new LinkedHashMap<String, Long>();
    var matchedGroups = new LinkedHashMap<String, Long>();
    var matchedRows =
        taskId == null
            ? sql.fetch(
                "select currency, count(*) as n, count(distinct group_key) as g"
                    + " from finance_reconciliation_match group by currency order by currency")
            : sql.fetch(
                "select currency, count(*) as n, count(distinct group_key) as g"
                    + " from finance_reconciliation_match where task_id = ? group by currency"
                    + " order by currency",
                taskId);
    for (var row : matchedRows) {
      var c = row.get("currency", String.class);
      matchedByCurrency.merge(c, row.get("n", Long.class), Long::sum);
      matchedGroups.merge(c, row.get("g", Long.class), Long::sum);
    }

    // ---- 未匹配（差异行）----
    var differenceRows =
        taskId == null
            ? sql.fetch(
                "select currency,difference_type,difference_amount"
                    + " from finance_reconciliation_difference")
            : sql.fetch(
                "select currency,difference_type,difference_amount"
                    + " from finance_reconciliation_difference where task_id = ?",
                taskId);

    var unmatchedByCurrency = new LinkedHashMap<String, Long>();
    var reasons = new LinkedHashMap<String, long[]>();
    for (var row : differenceRows) {
      var c = row.get("currency", String.class);
      var type = row.get("difference_type", String.class);
      var amount = row.get("difference_amount", BigDecimal.class);
      var reason = classify(type, amount, feeTolerance, exchangeTolerance);

      unmatchedByCurrency.merge(c, 1L, Long::sum);
      reasons.computeIfAbsent(c, k -> new long[FinanceMatchRuleReason.values().length])[reason.ordinal()]++;
    }

    // ---- 按币种出（🔴 绝不跨币种合计）----
    var currencies = new java.util.TreeSet<String>();
    currencies.addAll(matchedByCurrency.keySet());
    currencies.addAll(unmatchedByCurrency.keySet());

    var currencyRows = new ArrayList<FinanceMatchRateCurrencyRow>();
    var totalMatched = 0L;
    var totalUnmatched = 0L;
    for (var c : currencies) {
      var matched = matchedByCurrency.getOrDefault(c, 0L);
      var unmatched = unmatchedByCurrency.getOrDefault(c, 0L);
      var total = matched + unmatched;
      totalMatched += matched;
      totalUnmatched += unmatched;
      currencyRows.add(
          new FinanceMatchRateCurrencyRow(c, matched, unmatched, total, rate(matched, total)));
    }

    // ---- 原因分布（按币种）----
    var reasonRows = new ArrayList<FinanceMatchRateReasonRow>();
    for (var c : currencies) {
      var counters = reasons.get(c);
      var unmatchedInCurrency = unmatchedByCurrency.getOrDefault(c, 0L);
      for (var reason : FinanceMatchRuleReason.values()) {
        var count = counters == null ? 0L : counters[reason.ordinal()];
        if (count == 0) {
          continue;
        }
        reasonRows.add(
            new FinanceMatchRateReasonRow(
                c,
                reason.name(),
                reason.label(),
                count,
                ratio(count, unmatchedInCurrency)));
      }
    }

    var total = totalMatched + totalUnmatched;
    var rate = rate(totalMatched, total);
    var target = rule == null ? null : rule.get("match_rate_target", BigDecimal.class);
    Boolean targetMet = null;
    if (target != null && rate != null) {
      targetMet = rate.multiply(BigDecimal.valueOf(100)).compareTo(target) >= 0;
    }

    return new FinanceMatchRateMonitoring(
        taskId,
        taskNo,
        rule == null ? null : rule.get("id", UUID.class),
        rule == null ? "" : rule.get("rule_no", String.class),
        List.copyOf(currencyRows),
        List.copyOf(reasonRows),
        totalMatched,
        totalUnmatched,
        total,
        rate,
        target,
        targetMet,
        monitoringNote(rate, target, targetMet, rule == null));
  }

  /**
   * 算一次匹配率快照并**落库**（append-only 举证）。
   *
   * @param ruleId 口径规则（提供目标与口径快照）
   * @param taskId 对账任务（12.3.2.1）。**空 = 租户级汇总**。
   */
  @Transactional
  public List<FinanceMatchRateSnapshotRow> createRateSnapshot(
      UUID ruleId, UUID taskId, AccessService.Access actor) {
    var rule = requireRule(ruleId);
    var now = nowUtc();
    var monitoring = monitoring(taskId);

    var feeTolerance = rule.get("fee_tolerance", BigDecimal.class);
    var exchangeTolerance = rule.get("exchange_rate_tolerance", BigDecimal.class);

    // 原因分布按币种重算一次（快照要**按币种**各存一行）。
    var differenceRows =
        taskId == null
            ? sql.fetch(
                "select currency,difference_type,difference_amount"
                    + " from finance_reconciliation_difference")
            : sql.fetch(
                "select currency,difference_type,difference_amount"
                    + " from finance_reconciliation_difference where task_id = ?",
                taskId);
    var reasonCounts = new LinkedHashMap<String, Long>();
    var unmatchedByCurrency = new LinkedHashMap<String, Long>();
    for (var row : differenceRows) {
      var c = row.get("currency", String.class);
      var reason =
          classify(
              row.get("difference_type", String.class),
              row.get("difference_amount", BigDecimal.class),
              feeTolerance,
              exchangeTolerance);
      reasonCounts.merge(c + "|" + reason.name(), 1L, Long::sum);
      unmatchedByCurrency.merge(c, 1L, Long::sum);
    }

    var created = new ArrayList<FinanceMatchRateSnapshotRow>();
    for (var currencyRow : monitoring.currencies()) {
      var c = currencyRow.currency();
      var snapshotId = UUID.randomUUID();
      var rate = currencyRow.matchRate();
      var target = rule.get("match_rate_target", BigDecimal.class);
      var met =
          rate != null && rate.multiply(BigDecimal.valueOf(100)).compareTo(target) >= 0;

      insert(
          "finance_reconciliation_match_rate",
          List.of(
              column("id", snapshotId),
              column("tenant_id", actor.tenantId()),
              column("rule_id", ruleId),
              column("task_id", taskId),
              column("currency", c),
              column("matched_line_count", currencyRow.matchedLineCount()),
              column("matched_group_count", matchedGroupCount(currencyRow.currency(), taskId)),
              column("unmatched_line_count", currencyRow.unmatchedLineCount()),
              column("total_line_count", currencyRow.totalLineCount()),
              column("match_rate", rate),
              column("match_rate_target", target),
              column("target_met", met),
              column("rule_no", rule.get("rule_no", String.class)),
              column("rule_priority", rule.get("priority", Integer.class)),
              column("match_keys", pgTextArray(stringList(rule.get("match_keys", String[].class))), "::text[]"),
              column("min_dimension_count", rule.get("min_dimension_count", Integer.class)),
              column("date_window_days", rule.get("date_window_days", Integer.class)),
              column("amount_tolerance", rule.get("amount_tolerance", BigDecimal.class)),
              column("fee_tolerance", feeTolerance),
              column("exchange_rate_tolerance", exchangeTolerance),
              column("fuzzy_enabled", rule.get("fuzzy_enabled", Boolean.class)),
              column("computed_at", now, "::timestamptz"),
              column("computed_by", actor.userId()),
              column("created_at", now, "::timestamptz")));

      // ---- 原因行（按币种）----
      for (var reason : FinanceMatchRuleReason.values()) {
        var count = reasonCounts.getOrDefault(c + "|" + reason.name(), 0L);
        if (count == 0) {
          continue;
        }
        insert(
            "finance_reconciliation_match_rate_reason",
            List.of(
                column("id", UUID.randomUUID()),
                column("tenant_id", actor.tenantId()),
                column("snapshot_id", snapshotId),
                column("reason_type", reason.name()),
                column("line_count", count),
                column("ratio", ratio(count, currencyRow.unmatchedLineCount())),
                column("created_at", now, "::timestamptz")));
      }

      created.add(
          snapshotRow(
              snapshotId,
              taskId,
              c,
              currencyRow,
              matchedGroupCount(c, taskId),
              rule,
              met,
              now,
              actor.userId()));
    }

    appendEvent(
        actor.tenantId(),
        ruleId,
        "RATE_SNAPSHOT",
        rule.get("status", String.class),
        rule.get("status", String.class),
        "记录匹配率快照：匹配 " + monitoring.matchedLineCount() + " 行 / 未匹配 "
            + monitoring.unmatchedLineCount() + " 条"
            + (taskId == null ? "（租户级汇总）" : "（任务 " + monitoring.taskNo() + "）"),
        Map.of(
            "matchedLineCount", monitoring.matchedLineCount(),
            "unmatchedLineCount", monitoring.unmatchedLineCount(),
            "taskId", taskId == null ? "" : taskId.toString()),
        actor.userId(),
        now);

    return List.copyOf(created);
  }

  /** 匹配率快照历史（真分页；append-only）。 */
  public FinanceMatchRateSnapshotPage rateSnapshots(UUID ruleId, int page, int size) {
    requireRule(ruleId);
    var pageNo = Math.max(0, page);
    var pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    var total =
        sql.fetchOne(
                "select count(*) from finance_reconciliation_match_rate where rule_id = ?", ruleId)
            .get(0, Long.class);

    var rows =
        sql.fetch(
            "select id,rule_id,task_id,currency,matched_line_count,matched_group_count,"
                + " unmatched_line_count,total_line_count,match_rate,match_rate_target,target_met,"
                + " rule_no,rule_priority,match_keys,min_dimension_count,date_window_days,"
                + " amount_tolerance,fee_tolerance,exchange_rate_tolerance,fuzzy_enabled,"
                + " computed_at,computed_by from finance_reconciliation_match_rate"
                + " where rule_id = ? order by computed_at desc, id desc limit ? offset ?",
            ruleId,
            pageSize,
            pageNo * pageSize);

    return new FinanceMatchRateSnapshotPage(rows.map(this::snapshotRow), pageNo, pageSize, total);
  }

  // ================================================================ 内部

  /** 规则的引擎侧口径（校验过的入参）。 */
  record ResolvedSpec(
      int priority,
      List<String> matchKeys,
      int minDimensionCount,
      int dateWindowDays,
      BigDecimal amountTolerance,
      BigDecimal feeTolerance,
      BigDecimal exchangeRateTolerance,
      boolean fuzzyEnabled,
      int fuzzyReferencePrefixLength,
      int fuzzyDateWindowDays,
      BigDecimal fuzzyAmountTolerance,
      BigDecimal matchRateTarget,
      String currency,
      UUID accountId,
      UUID shopId) {}

  /**
   * 校验并归一入参。
   *
   * <p>🔴 **四道闸**：① 匹配键只能取 {订单号, 日期, 类型}（**金额不在里面**）且至少两个；
   * ② 至少对上的维度数 ≥ 2 且 ≤ 匹配键的维度数；③ 模糊窗口 ≥ 精确窗口；
   * ④ 金额类容差一律 ≥ 0。
   */
  private ResolvedSpec resolveSpec(FinanceMatchRuleInput input) {
    var keys = new ArrayList<String>();
    if (input.matchKeys() != null) {
      for (var raw : input.matchKeys()) {
        if (raw == null || raw.isBlank()) {
          continue;
        }
        var value = raw.trim().toUpperCase(Locale.ROOT);
        if (!MATCH_KEYS.contains(value)) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "未知的匹配键："
                  + raw
                  + "（可用："
                  + String.join("、", MATCH_KEYS)
                  + "）—— 🔴 **金额不在匹配键里**：卷 12 硬约束「金额相同只是候选，"
                  + "必须结合订单号、日期、类型」，金额只能作为容差 / 配平判据");
        }
        if (!keys.contains(value)) {
          keys.add(value);
        }
      }
    }
    if (keys.size() < 2) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "匹配键至少要有两个（订单号 / 日期 / 类型）—— 🔴 「金额相同即匹配」不构成匹配，"
              + "「金额 + 单个维度」也不构成匹配");
    }

    var minDimensions = input.minDimensionCount() == null ? 2 : input.minDimensionCount();
    if (minDimensions < 2) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "至少对上的维度数不得小于 2（金额 + 单个维度不构成匹配）");
    }
    if (minDimensions > keys.size()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "至少对上的维度数（" + minDimensions + "）不得超过匹配键的维度数（" + keys.size() + "）");
    }

    var windowDays = input.dateWindowDays() == null ? 7 : input.dateWindowDays();
    if (windowDays < 1 || windowDays > 31) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "日期窗口必须在 1 ~ 31 天之间（放到几百天等于用「金额相同」冒充匹配）");
    }

    var fuzzyEnabled = Boolean.TRUE.equals(input.fuzzyEnabled());
    var fuzzyWindow = input.fuzzyDateWindowDays() == null ? 31 : input.fuzzyDateWindowDays();
    if (fuzzyWindow < 1 || fuzzyWindow > 31) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "模糊兜底的日期窗口必须在 1 ~ 31 天之间");
    }
    if (fuzzyEnabled && fuzzyWindow < windowDays) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "模糊兜底的日期窗口（" + fuzzyWindow + " 天）不得小于精确阶段的窗口（" + windowDays + " 天）");
    }
    var fuzzyPrefix = input.fuzzyReferencePrefixLength() == null ? 8 : input.fuzzyReferencePrefixLength();
    if (fuzzyPrefix < 4 || fuzzyPrefix > 32) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "模糊兜底的订单号前缀长度必须在 4 ~ 32 之间（太短形同没有单号）");
    }

    var currency = input.currency() == null || input.currency().isBlank()
        ? null
        : input.currency().trim().toUpperCase(Locale.ROOT);
    if (currency != null && !currency.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是 3 位大写字母");
    }

    return new ResolvedSpec(
        input.priority() == null ? 100 : input.priority(),
        List.copyOf(keys),
        minDimensions,
        windowDays,
        money(input.amountTolerance()),
        money(input.feeTolerance()),
        money(input.exchangeRateTolerance()),
        fuzzyEnabled,
        fuzzyPrefix,
        fuzzyWindow,
        money(input.fuzzyAmountTolerance()),
        input.matchRateTarget() == null
            ? new BigDecimal("90").setScale(2, RoundingMode.HALF_UP)
            : input.matchRateTarget().setScale(2, RoundingMode.HALF_UP),
        currency,
        input.accountId(),
        input.shopId());
  }

  /** 分类一条差异 ⇒ 未匹配原因。 */
  static FinanceMatchRuleReason classify(
      String differenceType, BigDecimal differenceAmount, BigDecimal feeTolerance,
      BigDecimal exchangeTolerance) {
    if ("EXTERNAL_ONLY".equals(differenceType)) {
      return FinanceMatchRuleReason.EXTERNAL_ONLY;
    }
    if ("BOOK_ONLY".equals(differenceType)) {
      return FinanceMatchRuleReason.BOOK_ONLY;
    }
    if ("CURRENCY_MISMATCH".equals(differenceType)) {
      return FinanceMatchRuleReason.CURRENCY_MISMATCH;
    }
    if ("DIMENSION_MISMATCH".equals(differenceType)) {
      return FinanceMatchRuleReason.DIMENSION_MISMATCH;
    }
    // AMOUNT_MISMATCH：按残差落在哪个容差带里归因。
    var delta = differenceAmount == null ? BigDecimal.ZERO : differenceAmount.abs();
    if (feeTolerance != null && feeTolerance.signum() > 0 && delta.compareTo(feeTolerance) <= 0) {
      return FinanceMatchRuleReason.FEE_DIFFERENCE;
    }
    if (exchangeTolerance != null
        && exchangeTolerance.signum() > 0
        && delta.compareTo(exchangeTolerance) <= 0) {
      return FinanceMatchRuleReason.EXCHANGE_RATE_DIFFERENCE;
    }
    return FinanceMatchRuleReason.AMOUNT_MISMATCH;
  }

  /** 匹配率（分母为 0 ⇒ null，**不写 0**）。 */
  static BigDecimal rate(long matched, long total) {
    if (total <= 0) {
      return null;
    }
    return BigDecimal.valueOf(matched).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
  }

  /** 占比（分母为 0 ⇒ 0）。 */
  static BigDecimal ratio(long count, long total) {
    if (total <= 0) {
      return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(count).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
  }

  /** 某币种的匹配组数。 */
  private long matchedGroupCount(String currency, UUID taskId) {
    var row =
        taskId == null
            ? sql.fetchOne(
                "select count(distinct group_key) from finance_reconciliation_match"
                    + " where currency = ?",
                currency)
            : sql.fetchOne(
                "select count(distinct group_key) from finance_reconciliation_match"
                    + " where currency = ? and task_id = ?",
                currency,
                taskId);
    return row == null ? 0L : row.get(0, Long.class);
  }

  /** 监控结论里的话（蓝图原话：低于目标时该排查上游数据质量，而不是调规则）。 */
  private static String monitoringNote(
      BigDecimal rate, BigDecimal target, Boolean targetMet, boolean noRule) {
    if (noRule || target == null) {
      return "⚠️ **没有启用中的规则** ⇒ 没有匹配率目标可比 ⇒ 判不了达标与否。"
          + "先在规则集里启用一条规则（并设好匹配率目标），监控才有口径。";
    }
    if (rate == null) {
      return "⚠️ **分母为 0**（还没有匹配行也没有未匹配条）⇒ 匹配率**判不了**，"
          + "**不是 0%**。等有了真实的对账结果再看。";
    }
    if (Boolean.TRUE.equals(targetMet)) {
      return "✅ 匹配率 " + percent(rate) + " ≥ 目标 " + target.stripTrailingZeros().toPlainString()
          + "%：已达标。"
          + "🔴 蓝图口径：**匹配率是财务自动化的核心指标**，持续低于目标时应"
          + "**排查上游数据质量**（12.3.1.1 平台账单 / 12.7.1.2 银行流水）而非调规则。";
    }
    return "🔴 匹配率 " + percent(rate) + " **低于**目标 "
        + target.stripTrailingZeros().toPlainString() + "%。"
        + "蓝图口径：持续低于目标时应**排查上游数据质量**"
        + "（12.3.1.1 平台账单 / 12.7.1.2 银行流水）**而不是调规则** —— "
        + "先看下面的「未匹配原因分布」，再决定是改规则还是修上游。";
  }

  /** 百分号文案。 */
  static String percent(BigDecimal rate) {
    return rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
  }

  // ================================================================ 落库 / 行映射

  /** 一列（值 + 可选的 PG 类型转换）。 */
  record Column(String name, Object value, String cast) {}

  static Column column(String name, Object value) {
    return new Column(name, value, null);
  }

  static Column column(String name, Object value, String cast) {
    return new Column(name, value, cast);
  }

  /** 安全的 INSERT：列名与占位符**由列清单生成**。 */
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

  /** 追加一条规则事件（append-only 举证）。 */
  void appendEvent(
      UUID tenantId,
      UUID ruleId,
      String eventType,
      String fromStatus,
      String toStatus,
      String summary,
      Map<String, Object> detail,
      UUID actorId,
      OffsetDateTime now) {
    insert(
        "finance_reconciliation_match_rule_event",
        List.of(
            column("id", UUID.randomUUID()),
            column("tenant_id", tenantId),
            column("rule_id", ruleId),
            column("event_type", eventType),
            column("from_status", fromStatus),
            column("to_status", toStatus),
            column("summary", summary),
            column("detail", toJson(detail), "::jsonb"),
            column("actor_id", actorId),
            column("occurred_at", now, "::timestamptz"),
            column("created_at", now, "::timestamptz")));
  }

  FinanceMatchRuleRow ruleRow(Record r) {
    var status = r.get("status", String.class);
    return new FinanceMatchRuleRow(
        r.get("id", UUID.class),
        r.get("rule_no", String.class),
        r.get("rule_name", String.class),
        status,
        FinanceMatchRuleStatus.valueOf(status).label(),
        r.get("priority", Integer.class),
        stringList(r.get("match_keys", String[].class)),
        r.get("min_dimension_count", Integer.class),
        r.get("date_window_days", Integer.class),
        r.get("amount_tolerance", BigDecimal.class),
        r.get("fee_tolerance", BigDecimal.class),
        r.get("exchange_rate_tolerance", BigDecimal.class),
        r.get("fuzzy_enabled", Boolean.class),
        r.get("fuzzy_reference_prefix_length", Integer.class),
        r.get("fuzzy_date_window_days", Integer.class),
        r.get("fuzzy_amount_tolerance", BigDecimal.class),
        r.get("match_rate_target", BigDecimal.class),
        r.get("currency", String.class),
        r.get("account_id", UUID.class),
        r.get("shop_id", UUID.class),
        r.get("note", String.class),
        r.get("version", Integer.class) == null ? 0L : r.get("version", Integer.class).longValue(),
        r.get("activated_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  FinanceMatchRuleEventRow eventRow(Record r) {
    var eventType = r.get("event_type", String.class);
    return new FinanceMatchRuleEventRow(
        r.get("id", UUID.class),
        r.get("rule_id", UUID.class),
        eventType,
        eventLabel(eventType),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("summary", String.class),
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  FinanceMatchRateSnapshotRow snapshotRow(Record r) {
    var rate = r.get("match_rate", BigDecimal.class);
    var target = r.get("match_rate_target", BigDecimal.class);
    return new FinanceMatchRateSnapshotRow(
        r.get("id", UUID.class),
        r.get("rule_id", UUID.class),
        r.get("rule_no", String.class),
        r.get("task_id", UUID.class),
        r.get("currency", String.class),
        r.get("matched_line_count", Long.class),
        r.get("matched_group_count", Long.class),
        r.get("unmatched_line_count", Long.class),
        r.get("total_line_count", Long.class),
        rate,
        target,
        r.get("target_met", Boolean.class),
        stringList(r.get("match_keys", String[].class)),
        r.get("min_dimension_count", Integer.class),
        r.get("date_window_days", Integer.class),
        r.get("amount_tolerance", BigDecimal.class),
        r.get("fee_tolerance", BigDecimal.class),
        r.get("exchange_rate_tolerance", BigDecimal.class),
        r.get("fuzzy_enabled", Boolean.class),
        r.get("computed_at", OffsetDateTime.class),
        r.get("computed_by", UUID.class));
  }

  private FinanceMatchRateSnapshotRow snapshotRow(
      UUID id,
      UUID taskId,
      String currency,
      FinanceMatchRateCurrencyRow currencyRow,
      long groupCount,
      Record rule,
      boolean met,
      OffsetDateTime now,
      UUID actorId) {
    return new FinanceMatchRateSnapshotRow(
        id,
        rule.get("id", UUID.class),
        rule.get("rule_no", String.class),
        taskId,
        currency,
        currencyRow.matchedLineCount(),
        groupCount,
        currencyRow.unmatchedLineCount(),
        currencyRow.totalLineCount(),
        currencyRow.matchRate(),
        rule.get("match_rate_target", BigDecimal.class),
        met,
        stringList(rule.get("match_keys", String[].class)),
        rule.get("min_dimension_count", Integer.class),
        rule.get("date_window_days", Integer.class),
        rule.get("amount_tolerance", BigDecimal.class),
        rule.get("fee_tolerance", BigDecimal.class),
        rule.get("exchange_rate_tolerance", BigDecimal.class),
        rule.get("fuzzy_enabled", Boolean.class),
        now,
        actorId);
  }

  /** 规则行（不存在 ⇒ 404）。 */
  Record requireRule(UUID id) {
    var row = sql.fetchOne("select * from finance_reconciliation_match_rule where id = ?", id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "匹配规则不存在：" + id);
    }
    return row;
  }

  // ================================================================ 工具

  static OffsetDateTime nowUtc() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  static BigDecimal money(BigDecimal value) {
    if (value == null) {
      return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  static String trim(String value) {
    return value == null ? "" : value.trim();
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

  /** 规则号：MR-日期-8 位十六进制（租户内唯一、可读）。 */
  String ruleNo() {
    return RULE_NO_PREFIX
        + nowUtc().toLocalDate().toString().replace("-", "")
        + "-"
        + UUID.randomUUID().toString().substring(0, 8);
  }

  static String eventLabel(String eventType) {
    return switch (eventType) {
      case "RULE_CREATED" -> "建立匹配规则";
      case "RULE_UPDATED" -> "改动匹配规则";
      case "RULE_ACTIVATED" -> "启用匹配规则";
      case "RULE_DISABLED" -> "停用匹配规则";
      case "RATE_SNAPSHOT" -> "记录匹配率快照";
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
