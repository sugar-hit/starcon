package com.starcon.finance;

import static com.starcon.finance.DeferredSettlementModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * 12.3.1.3 延迟结算。
 *
 * <p>蓝图 12-finance.md：「管理平台延迟结算（部分平台按周期后结算或滚动结算）：单列在途未结算金额、结算条件、预计到账时间，支持查询「订单已发货但钱未到账」的明细。」输入：「结算规则；订单与结算状态。」输出：「延迟结算明细 +
 * 在途资金统计 + 预计到账时间。」关系：「上游 = 12.3.1.1、9 订单；下游 = 13 利润（按已结算/未结算区分口径）。」
 *
 * <p><b>本模块的四条设计主线</b>：
 *
 * <ol>
 *   <li><b>不另起结算体系。</b> 结算单 / 结算明细行仍以 12.3.1.1（V345）与 12.3.1.2（V348）为准，本模块只承载
 *       「在途未结算金额、结算条件、预计到账时间、已发货未到账的明细」这一层。「已结算口径」的钱由 DB 触发器从结算明细行
 *       <b>派生</b>（V353 的派生链），本模块**不维护第二份到账金额**。
 *   <li><b>两套口径并存，分币种，绝不相加。</b> 下游 13 利润要求「按已结算 / 未结算区分口径」⇒ 本模块同时给出
 *       <b>订单口径</b>（平台订单总额，毛额）与 <b>已结算口径</b>（已到账结算单里的销售额行合计），
 *       以及两者之差 <b>在途未结算金额</b>。口径定义写在 {@link FinanceDeferredSettlementBasisNote} 里随数据返回。
 *   <li><b>时间不许静默偏 8 小时。</b> 发货时间 / 结算周期区间 / 预计到账时间全是 {@code timestamptz}，
 *       一律 OffsetDateTime + {@code ?::timestamptz}；预计到账时间由 <b>DB 触发器</b>从「发货时间 + 结算模式 +
 *       周期 + 到账偏移」派生，服务层写不进去。
 *   <li><b>上游没读到就说没读到，绝不造数。</b> trade 的平台订单 / 发货记录经授权 HTTP 只读端口取；取不到一律 503
 *       并写明「读不到」。12.3.2.1 对账任务与 13 利润<b>未交付</b> ⇒ 本模块不做自动匹配、不产出利润数字。
 * </ol>
 */
@Service
public class DeferredSettlementService {

  private static final Logger logger = LoggerFactory.getLogger(DeferredSettlementService.class);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 扫描时告警最多列多少条（超出只给条数，不给全文 —— 否则一屏刷不出问题）。 */
  static final int MAX_WARNINGS = 50;

  private final DSLContext sql;
  private final TradeOrderShipmentPort trade;

  public DeferredSettlementService(DSLContext sql, TradeOrderShipmentPort trade) {
    this.sql = sql;
    this.trade = trade;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：**每一维说清「有没有、依据是什么、缺了会怎样」**。
   *
   * <p>⚠️ 本模块的缺口必须分开说：自动对账任务（12.3.2.1，**本服务**的后续模块）与利润（13，**别的**服务）
   * —— 把两者都写成「不可用」，运维会以为要去催结算，而实际上后两者是本服务内部的事。
   */
  public FinanceDeferredSettlementCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceDeferredSettlementCapability>();
    var gaps = new ArrayList<FinanceDeferredSettlementDownstreamGap>();

    capabilities.add(
        new FinanceDeferredSettlementCapability(
            "12.3.1.3",
            "延迟结算明细（订单级台账）",
            true,
            "finance_deferred_settlement（订单口径 / 已结算口径 / 在途未结算 / 发货时间 / 预计到账时间）",
            "",
            "台账已落地：数据落 PostgreSQL 16，查询走 jOOQ 显式 SQL，全链路 BigDecimal + NUMERIC(19,4)，无浮点。"
                + "**在途未结算金额与已结算口径金额由 DB 触发器派生**，服务层与直连 SQL 都写不进一个对不上的数。"));
    capabilities.add(
        new FinanceDeferredSettlementCapability(
            "12.3.1.3",
            "结算规则（周期后结算 / 滚动结算）",
            true,
            "finance_deferred_settlement_rule（平台 + 店铺 → 模式 + 周期 + 到账偏移）",
            "",
            "蓝图输入里的「结算规则」是**本模块自己的输入**：店铺级规则优先，平台级默认规则兜底；"
                + "预计到账时间 = 结算周期结束 + 到账偏移（**由 DB 触发器派生**，服务层写不进去）。"));
    capabilities.add(
        new FinanceDeferredSettlementCapability(
            "12.3.1.3",
            "在途资金统计（两套口径，分币种）",
            true,
            "GET /finance/deferred-settlement/summary → 按币种的订单口径 / 已结算口径 / 在途未结算",
            "",
            "下游 13 利润要的两套口径**同时**给出，**分币种**分组（USD 与 EUR 加起来不是任何一个金额），"
                + "**绝不跨币种相加**。口径定义随响应返回（basis），半年后仍说得清「订单口径」是毛额还是净额。"));
    capabilities.add(
        new FinanceDeferredSettlementCapability(
            "9.1.2.1 / 9.4.1.2",
            "平台订单与发货记录（上游 9 订单）",
            true,
            "GET /trade/platform-orders/orders + GET /trade/fulfillment/shipment-records/records（经 TradeOrderShipmentPort 授权 HTTP 只读）",
            "",
            "「订单已发货但钱未到账」必须同时有订单事实与发货事实，所以经 trade 的**授权 HTTP API** 读："
                + "跨服务**不直连对方库、不建跨库 FK / 视图**。租户由 trade 侧按已认证成员关系解析（本端口只转发调用方 bearer）。"));
    capabilities.add(
        new FinanceDeferredSettlementCapability(
            "12.3.1.1",
            "已结算口径的事实来源（结算单与结算明细行）",
            true,
            "finance_settlement / finance_settlement_line（只读；DB 触发器派生已结算口径金额）",
            "",
            "本模块**不改** 12.3.1.1 / 12.3.1.2 的任何结构：「钱到账了没」由结算单状态与结算明细行决定，"
                + "**已结算口径金额按 DB 触发器派生**（只认已到账结算单里的销售额行）。"));
    capabilities.add(
        new FinanceDeferredSettlementCapability(
            "12.3.2.1",
            "自动对账任务",
            false,
            "本模块只算两套口径与在途金额，不实现自动匹配",
            "12.3.2.1",
            "「订单金额与结算金额对不对得上」的**自动匹配**、匹配结果与差异分类属 12.3.2.1。"
                + "本模块**不实现**匹配、不产出差异分类 —— 那是别的模块的职责。"));
    capabilities.add(
        new FinanceDeferredSettlementCapability(
            "13.1.1.1",
            "利润与经营分析",
            false,
            "reporting 服务不在本仓（编排闸 serviceBlocked）",
            "13 利润与经营分析",
            "延迟结算是它的一处上游输入。本模块**不产出任何利润数字**，但把两套口径与在途未结算金额**分币种**算好了 —— "
                + "下游不必自己重算一遍「哪些订单的钱还没到账」。"));

    gaps.add(
        new FinanceDeferredSettlementDownstreamGap(
            "12.3.2.1",
            "对账任务",
            "finance",
            false,
            "12.3 结算与对账",
            "本模块只给两套口径与在途金额；自动匹配与差异分类未交付。"));
    gaps.add(
        new FinanceDeferredSettlementDownstreamGap(
            "13.1.1.1",
            "利润与经营分析",
            "analytics",
            false,
            "13 利润与经营分析",
            "13 未交付：延迟结算是它的一处上游输入。"
                + "**本模块已把两套口径（订单口径 / 已结算口径）分币种备好**，接上动作只需取数，不需要重算。"));
    gaps.add(
        new FinanceDeferredSettlementDownstreamGap(
            "卷 02 同步",
            "平台结算报告自动同步",
            "channel",
            false,
            "2 渠道与店铺",
            "上游没有结算报告端点 ⇒ 自动同步不可用；"
                + "本模块的「已结算口径」来自 12.3.1.1 的人工上传账单 / 人工登记结算单（那条路径是通的）。"));

    return new FinanceDeferredSettlementCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 口径说明

  /**
   * 两套口径的定义说明。
   *
   * <p>⚠️ **随每个汇总响应一起返回**：脱离口径说明的数字，半年后没人说得清「订单口径」是毛额还是净额。
   */
  public FinanceDeferredSettlementBasisNote basis() {
    return new FinanceDeferredSettlementBasisNote(
        "订单口径（ORDER_BASIS）：平台订单总额（毛额，含税含运），来自 trade 的平台订单。"
            + "含义 = 按「订单已发生」确认收入，钱还没到账也算。",
        "已结算口径（SETTLED_BASIS）：该订单在**已到账**结算单（status ∈ PARTIAL/SETTLED）里的**销售额（SALES）行**合计。"
            + "含义 = 按「钱已到账」确认收入。只认销售额行：佣金 / 退款 / FBA 费用 / 代扣税是平台从货款里扣走的钱，"
            + "不是「货款没到账」，算进去会让在途未结算金额虚高。",
        "在途未结算金额（IN_TRANSIT）：逐行 max(订单口径 − 已结算口径, 0)。"
            + "正常情况下逐行满足「订单口径 = 已结算口径 + 在途未结算」。",
        "口径基数差：订单口径 − 已结算口径 − 在途未结算。不为 0 说明存在「已结算口径 > 订单口径」的订单"
            + "（结算单按含税口径记销售额），在途未结算被截到了 0 —— 这一格如实报出，不藏。",
        "所有金额**按币种分组**，绝不跨币种相加（USD 与 EUR 加起来不是任何一个金额）。");
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinanceDeferredSettlementOptions options() {
    var statuses =
        sql.fetch("select distinct status from finance_deferred_settlement order by status")
            .map(r -> r.get(0, String.class));
    var platforms =
        sql.fetch("select distinct platform_code from finance_deferred_settlement order by platform_code")
            .map(r -> r.get(0, String.class));
    var shops =
        sql.fetch(
                "select distinct shop_code from finance_deferred_settlement"
                    + " where shop_code <> '' order by shop_code")
            .map(r -> r.get(0, String.class));
    var currencies =
        sql.fetch("select distinct currency from finance_deferred_settlement order by currency")
            .map(r -> r.get(0, String.class));
    var modes =
        sql.fetch("select distinct settlement_mode from finance_deferred_settlement order by settlement_mode")
            .map(r -> r.get(0, String.class));
    return new FinanceDeferredSettlementOptions(statuses, platforms, shops, currencies, modes);
  }

  // ================================================================ 结算规则

  /** 结算规则清单（真数据）。 */
  public List<FinanceDeferredSettlementRuleRow> rules() {
    return sql.fetch(
            "select * from finance_deferred_settlement_rule"
                + " order by platform_code, shop_code nulls last, id")
        .map(this::ruleRow);
  }

  /**
   * 登记 / 改写一条结算规则（按「平台 + 店铺」upsert）。
   *
   * <p>⚠️ {@code shopCode} 为空表示**平台级默认规则**：该平台下没有店铺级规则的店铺都用它。
   */
  @Transactional
  public FinanceDeferredSettlementRuleRow upsertRule(
      FinanceDeferredSettlementRuleInput input, AccessService.Access actor) {
    if (input == null) throw badRequest("结算规则内容不能为空");
    String platform =
        input.platformCode() == null ? "" : input.platformCode().trim().toLowerCase(Locale.ROOT);
    if (platform.isEmpty()) throw badRequest("平台不能为空");
    if (input.settlementMode() == null) throw badRequest("结算模式不能为空");
    if (input.cycleDays() <= 0 || input.cycleDays() > 365) {
      throw badRequest("结算周期天数必须在 1 到 365 之间");
    }
    if (input.payoutLagDays() < 0 || input.payoutLagDays() > 365) {
      throw badRequest("到账偏移天数必须在 0 到 365 之间");
    }
    String shop = input.shopCode() == null || input.shopCode().isBlank() ? null : input.shopCode().trim();
    String currency =
        input.currency() == null || input.currency().isBlank()
            ? null
            : input.currency().trim().toUpperCase(Locale.ROOT);
    if (currency != null && !currency.matches("[A-Z]{3}")) throw badRequest("币种必须是三位大写字母");
    String status = input.status() == null || input.status().isBlank() ? "ACTIVE" : input.status().trim().toUpperCase(Locale.ROOT);
    if (!status.equals("ACTIVE") && !status.equals("INACTIVE")) {
      throw badRequest("规则状态只能是 ACTIVE 或 INACTIVE");
    }
    String note = input.conditionNote() == null ? "" : input.conditionNote().trim();

    var existing =
        sql.fetchOne(
            "select id from finance_deferred_settlement_rule"
                + " where tenant_id = ? and platform_code = ? and shop_code is not distinct from ?"
                + " and status = 'ACTIVE'",
            actor.tenantId(),
            platform,
            shop);

    if (existing == null) {
      var id = UUID.randomUUID();
      sql.execute(
          "insert into finance_deferred_settlement_rule"
              + "(id,tenant_id,platform_code,shop_code,shop_id,settlement_mode,cycle_days,payout_lag_days,"
              + " currency,condition_note,status,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          actor.tenantId(),
          platform,
          shop,
          input.shopId(),
          input.settlementMode().name(),
          input.cycleDays(),
          input.payoutLagDays(),
          currency,
          note,
          status,
          OffsetDateTime.now(ZoneOffset.UTC),
          OffsetDateTime.now(ZoneOffset.UTC));
      return ruleRow(sql.fetchOne("select * from finance_deferred_settlement_rule where id = ?", id));
    }

    sql.execute(
        "update finance_deferred_settlement_rule set shop_id = ?, settlement_mode = ?, cycle_days = ?,"
            + " payout_lag_days = ?, currency = ?, condition_note = ?, status = ?, updated_at = ?::timestamptz"
            + " where id = ?",
        input.shopId(),
        input.settlementMode().name(),
        input.cycleDays(),
        input.payoutLagDays(),
        currency,
        note,
        status,
        OffsetDateTime.now(ZoneOffset.UTC),
        existing.get("id", UUID.class));
    return ruleRow(
        sql.fetchOne("select * from finance_deferred_settlement_rule where id = ?", existing.get("id", UUID.class)));
  }

  // ================================================================ 列表 / 详情 / 统计

  /**
   * 延迟结算明细列表：真分页 + 真筛选 + 真排序。
   *
   * <p>⚠️ **汇总覆盖的是整个筛选结果集，不是当前页**：分页一变汇总就变的话，
   * 「翻到第二页看到总额变小了」会被读成「数据丢了」。
   */
  public FinanceDeferredSettlementPage page(DeferredSettlementQuery query) {
    int p = Math.max(0, query.page());
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from finance_deferred_settlement d" + clause, binds.toArray())
            .get(0, Long.class);
    List<FinanceDeferredSettlementRow> items =
        sql.fetch(
                "select d.*, s.settlement_no as settlement_no from finance_deferred_settlement d"
                    + " left join finance_settlement s"
                    + " on s.tenant_id = d.tenant_id and s.id = d.settlement_id"
                    + clause
                    + " order by d.expected_arrived_at asc, d.id asc limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::row);

    return new FinanceDeferredSettlementPage(
        items, p, s, total, summaryByCurrency(where, binds), basis());
  }

  /**
   * 在途资金统计（蓝图输出之一）。
   *
   * <p>⚠️ **统计覆盖整个筛选结果集**，与列表页保持同一个口径 —— 否则「列表说 3 笔在途、统计说 8 笔」没法解释。
   */
  public FinanceDeferredSettlementSummary summary(DeferredSettlementQuery query) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    applyFilters(query, where, binds);
    var summaries = summaryByCurrency(where, binds);

    var pendingWhere = new ArrayList<String>(where);
    pendingWhere.add("d.status = 'PENDING'");
    var earliest =
        sql.fetchOne(
                "select min(d.expected_arrived_at) as earliest from finance_deferred_settlement d where "
                    + String.join(" and ", pendingWhere),
                binds.toArray())
            .get("earliest", OffsetDateTime.class);
    long days = 0;
    if (earliest != null) {
      days = ChronoUnit.DAYS.between(OffsetDateTime.now(ZoneOffset.UTC), earliest);
    }
    return new FinanceDeferredSettlementSummary(summaries, basis(), earliest, days, OffsetDateTime.now(ZoneOffset.UTC));
  }

  /**
   * 按币种的**两套口径**汇总。
   *
   * <p>⚠️ **作废的台账行不计入**：它在资金口径上不存在，把它们算进「在途未结算金额」会让余额虚高。
   */
  private List<FinanceDeferredSettlementCurrencySummary> summaryByCurrency(
      List<String> where, List<Object> binds) {
    var clauses = new ArrayList<String>();
    clauses.add("d.status <> 'VOID'");
    clauses.addAll(where);
    return sql.fetch(
            "select d.currency,"
                + " sum(d.order_amount) as order_basis,"
                + " sum(d.settled_amount) as settled_basis,"
                + " sum(d.unsettled_amount) as in_transit,"
                + " count(*) as cnt,"
                + " count(*) filter (where d.status = 'PENDING') as pending_count,"
                + " count(*) filter (where d.status = 'SETTLED') as settled_count,"
                + " count(*) filter (where d.status = 'PENDING' and d.expected_arrived_at < now()) as overdue_count"
                + " from finance_deferred_settlement d where "
                + String.join(" and ", clauses)
                + " group by d.currency order by d.currency",
            binds.toArray())
        .map(
            r -> {
              BigDecimal orderBasis = nz(r.get("order_basis", BigDecimal.class));
              BigDecimal settledBasis = nz(r.get("settled_basis", BigDecimal.class));
              BigDecimal inTransit = nz(r.get("in_transit", BigDecimal.class));
              BigDecimal gap = orderBasis.subtract(settledBasis).subtract(inTransit);
              return new FinanceDeferredSettlementCurrencySummary(
                  r.get("currency", String.class),
                  orderBasis.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                  settledBasis.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                  inTransit.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                  gap.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                  r.get("cnt", Long.class),
                  r.get("pending_count", Long.class),
                  r.get("settled_count", Long.class),
                  r.get("overdue_count", Long.class));
            });
  }

  /** 台账明细：单头 + 事件流。 */
  public FinanceDeferredSettlementDetail detail(UUID id) {
    var head =
        sql.fetchOne(
            "select d.*, s.settlement_no as settlement_no from finance_deferred_settlement d"
                + " left join finance_settlement s on s.tenant_id = d.tenant_id and s.id = d.settlement_id"
                + " where d.id = ?",
            id);
    if (head == null) throw notFound("延迟结算台账行 " + id + " 不存在或无权查看");
    var events =
        sql.fetch(
                "select * from finance_deferred_settlement_event where deferred_settlement_id = ?"
                    + " order by occurred_at, id",
                id)
            .map(
                r ->
                    new FinanceDeferredSettlementEventRow(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("reason", String.class),
                        r.get("actor_id", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class)));
    return new FinanceDeferredSettlementDetail(row(head), events);
  }

  private void applyFilters(DeferredSettlementQuery q, List<String> where, List<Object> binds) {
    if (q.status() != null) {
      where.add("d.status = ?");
      binds.add(q.status().name());
    }
    if (q.platformCode() != null && !q.platformCode().isBlank()) {
      where.add("d.platform_code = ?");
      binds.add(q.platformCode().trim().toLowerCase(Locale.ROOT));
    }
    if (q.shopCode() != null && !q.shopCode().isBlank()) {
      where.add("d.shop_code = ?");
      binds.add(q.shopCode().trim());
    }
    if (q.currency() != null && !q.currency().isBlank()) {
      where.add("d.currency = ?");
      binds.add(q.currency().trim().toUpperCase(Locale.ROOT));
    }
    if (q.shippedFrom() != null) {
      where.add("d.shipped_at >= ?::timestamptz");
      binds.add(q.shippedFrom());
    }
    if (q.shippedTo() != null) {
      where.add("d.shipped_at <= ?::timestamptz");
      binds.add(q.shippedTo());
    }
    if (q.overdueOnly() != null && q.overdueOnly()) {
      where.add("d.status = 'PENDING' and d.expected_arrived_at < now()");
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add(
          "(d.order_no ilike ? or d.external_order_no ilike ? or d.shop_code ilike ?"
              + " or d.condition_note ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
  }

  // ================================================================ 扫描入台账

  /**
   * 从 trade 扫描「已发货但钱未到账」的订单进延迟结算台账。
   *
   * <p>⚠️ **取不到上游就 503**，绝不返回空集合冒充「平台没有订单」。
   *
   * <p>⚠️ **跳过也要说清原因**：一笔都没进来时，现场需要知道是「平台没有订单」还是「没有生效的结算规则」。
   */
  @Transactional
  public FinanceDeferredSettlementScanResult scan(String bearer, AccessService.Access actor) {
    var rules = activeRules();
    var warnings = new ArrayList<String>();
    int skipped = 0;

    // ---- 上游：平台订单 ----
    var orders = new ArrayList<TradeOrderShipmentPort.TradePlatformOrderRow>();
    boolean truncated = false;
    long total = Long.MAX_VALUE;
    for (int p = 0; p < TradeOrderShipmentPort.MAX_PAGES; p++) {
      var page = trade.orders(p, bearer);
      if (page.rows().isEmpty()) break;
      orders.addAll(page.rows());
      total = page.total();
      if (orders.size() >= total) break;
      if (p == TradeOrderShipmentPort.MAX_PAGES - 1) truncated = true;
    }
    int fetchedOrders = orders.size();

    // ---- 上游：发货记录 ----
    var shipments = new ArrayList<TradeOrderShipmentPort.TradeShipmentRecordRow>();
    long shipmentTotal = Long.MAX_VALUE;
    for (int p = 0; p < TradeOrderShipmentPort.MAX_PAGES; p++) {
      var page = trade.shipments(p, bearer);
      if (page.items().isEmpty()) break;
      shipments.addAll(page.items());
      shipmentTotal = page.totalElements();
      if (shipments.size() >= shipmentTotal) break;
      if (p == TradeOrderShipmentPort.MAX_PAGES - 1) truncated = true;
    }
    int fetchedShipments = shipments.size();

    // ---- 发货记录索引（一个订单可能有多条发货记录，取**最早**的那条：订单是那时发出的）----
    var bySalesOrder = new HashMap<UUID, TradeOrderShipmentPort.TradeShipmentRecordRow>();
    for (var s : shipments) {
      if (s.salesOrderId() == null) continue;
      if (TradeOrderShipmentPort.VOID_STATUS.equals(s.status())) continue;
      if (s.shippedAt() == null) continue;
      var known = bySalesOrder.get(s.salesOrderId());
      if (known == null || s.shippedAt().isBefore(known.shippedAt())) {
        bySalesOrder.put(s.salesOrderId(), s);
      }
    }

    // ---- 逐单匹配 ----
    int matched = 0;
    int created = 0;
    int updated = 0;
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    for (var order : orders) {
      if (TradeOrderShipmentPort.CANCELLED.equals(order.normalizedStatus())) {
        skipped++;
        warn(warnings, "订单 " + nz(order.externalOrderNo()) + " 已取消，不进延迟结算台账");
        continue;
      }
      if (order.platformGrandTotalAmount() == null) {
        skipped++;
        warn(warnings, "平台订单 " + nz(order.externalOrderNo()) + " 没有订单总额，无法登记延迟结算");
        continue;
      }
      String currency = order.currency() == null ? "" : order.currency().trim().toUpperCase(Locale.ROOT);
      if (currency.isEmpty()) {
        skipped++;
        warn(warnings, "平台订单 " + nz(order.externalOrderNo()) + " 没有币种，无法登记延迟结算");
        continue;
      }
      var shipment = order.salesOrderId() == null ? null : bySalesOrder.get(order.salesOrderId());
      if (shipment == null) {
        skipped++;
        warn(warnings, "平台订单 " + nz(order.externalOrderNo()) + " 还没有发货记录，不进延迟结算台账");
        continue;
      }
      var rule = resolveRule(rules, order.platformCode(), order.shopCode());
      if (rule == null) {
        skipped++;
        warn(
            warnings,
            "平台订单 "
                + nz(order.externalOrderNo())
                + " 所在的平台/店铺没有生效的结算规则，先登记结算规则再扫描");
        continue;
      }
      if (rule.get("currency", String.class) != null
          && !rule.get("currency", String.class).equalsIgnoreCase(currency)) {
        skipped++;
        warn(
            warnings,
            "平台订单 "
                + nz(order.externalOrderNo())
                + " 的币种 "
                + currency
                + " 与结算规则限定的币种不一致，不进延迟结算台账");
        continue;
      }
      matched++;

      var platformOrderId = order.id();
      var existing =
          sql.fetchOne(
              "select id, status from finance_deferred_settlement"
                  + " where tenant_id = ? and platform_order_id = ?",
              actor.tenantId(),
              platformOrderId);
      String orderNo = nz(order.orderNo()).isEmpty() ? nz(order.externalOrderNo()) : order.orderNo();
      String externalOrderNo = nz(order.externalOrderNo()).isEmpty() ? orderNo : order.externalOrderNo();

      if (existing == null) {
        var id = UUID.randomUUID();
        sql.execute(
            "insert into finance_deferred_settlement"
                + "(id,tenant_id,rule_id,platform_order_id,sales_order_id,order_no,external_order_no,"
                + " platform_code,shop_code,currency,order_amount,shipped_at,shipment_status,"
                + " settlement_mode,cycle_days,payout_lag_days,settlement_cycle_start,settlement_cycle_end,"
                + " expected_arrived_at,condition_note,source,scanned_at,created_at,updated_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,null,null,null,?,'TRADE_SCAN',"
                + "?::timestamptz,?::timestamptz,?::timestamptz)",
            id,
            actor.tenantId(),
            rule.get("id", UUID.class),
            platformOrderId,
            order.salesOrderId(),
            orderNo,
            externalOrderNo,
            nz(order.platformCode()),
            nz(order.shopCode()),
            currency,
            order.platformGrandTotalAmount(),
            shipment.shippedAt(),
            nz(shipment.status()),
            rule.get("settlement_mode", String.class),
            rule.get("cycle_days", Integer.class),
            rule.get("payout_lag_days", Integer.class),
            nz(rule.get("condition_note", String.class)),
            now,
            now,
            now);
        created++;
        appendEvent(actor.tenantId(), id, "SCANNED", "", "", "扫描自 trade 的平台订单与发货记录", actor.userId(), now);
      } else {
        var id = existing.get("id", UUID.class);
        var fromStatus = nz(existing.get("status", String.class));
        sql.execute(
            "update finance_deferred_settlement set rule_id = ?, sales_order_id = ?, order_no = ?,"
                + " external_order_no = ?, platform_code = ?, shop_code = ?, currency = ?, order_amount = ?,"
                + " shipped_at = ?::timestamptz, shipment_status = ?, settlement_mode = ?, cycle_days = ?,"
                + " payout_lag_days = ?, condition_note = ?, scanned_at = ?::timestamptz, updated_at = ?::timestamptz"
                + " where id = ?",
            rule.get("id", UUID.class),
            order.salesOrderId(),
            orderNo,
            externalOrderNo,
            nz(order.platformCode()),
            nz(order.shopCode()),
            currency,
            order.platformGrandTotalAmount(),
            shipment.shippedAt(),
            nz(shipment.status()),
            rule.get("settlement_mode", String.class),
            rule.get("cycle_days", Integer.class),
            rule.get("payout_lag_days", Integer.class),
            nz(rule.get("condition_note", String.class)),
            now,
            now,
            id);
        updated++;
        var after = sql.fetchOne("select status from finance_deferred_settlement where id = ?", id);
        var toStatus = after == null ? "" : nz(after.get("status", String.class));
        if (!fromStatus.equals(toStatus)) {
          appendEvent(actor.tenantId(), id, "SETTLED", fromStatus, toStatus, "结算单到账状态变化", actor.userId(), now);
        } else {
          appendEvent(actor.tenantId(), id, "UPDATED", fromStatus, toStatus, "扫描刷新了订单事实", actor.userId(), now);
        }
      }
    }

    if (truncated) {
      warn(
          warnings,
          "上游翻页触到上限（"
              + TradeOrderShipmentPort.PAGE_SIZE
              + " × "
              + TradeOrderShipmentPort.MAX_PAGES
              + "），还有订单没扫进来；请缩小范围后重扫");
    }
    if (matched == 0 && orders.isEmpty()) {
      warn(warnings, "trade 没有返回任何平台订单 —— 台账是空的");
    }
    logger.info(
        "延迟结算扫描完成：订单 {} 笔，发货记录 {} 笔，命中 {} 笔，新建 {} 笔，更新 {} 笔，跳过 {} 笔",
        fetchedOrders,
        fetchedShipments,
        matched,
        created,
        updated,
        skipped);
    return new FinanceDeferredSettlementScanResult(
        fetchedOrders, fetchedShipments, matched, created, updated, skipped, truncated, warnings);
  }

  /**
   * 规则解析：**店铺级优先，平台级默认兜底**。
   *
   * <p>⚠️ 取不到就返回 {@code null}，由调用方**如实**记成一条跳过原因 —— 不猜一条规则顶替。
   */
  private Record resolveRule(Map<String, Record> rules, String platformCode, String shopCode) {
    String platform = platformCode == null ? "" : platformCode.trim().toLowerCase(Locale.ROOT);
    String shop = shopCode == null ? "" : shopCode.trim();
    if (platform.isEmpty()) return null;
    var shopRule = rules.get(platform + "|" + shop);
    if (shopRule != null) return shopRule;
    return rules.get(platform + "|");
  }

  /** 生效规则索引：{@code 平台|店铺} → 规则；平台级默认规则的键是 {@code 平台|}。 */
  private Map<String, Record> activeRules() {
    var rules = new LinkedHashMap<String, Record>();
    for (var r : sql.fetch("select * from finance_deferred_settlement_rule where status = 'ACTIVE'")) {
      String platform = nz(r.get("platform_code", String.class)).toLowerCase(Locale.ROOT);
      String shop = nz(r.get("shop_code", String.class));
      rules.put(platform + "|" + shop, r);
    }
    return rules;
  }

  // ================================================================ 作废

  /** 作废（红冲的等价物）：终态，不进任何口径。理由必填。 */
  @Transactional
  public FinanceDeferredSettlementRow voidRow(
      UUID id, FinanceDeferredSettlementVoidInput input, AccessService.Access actor) {
    if (input == null || input.reason() == null || input.reason().isBlank()) {
      throw badRequest("作废必须写明理由");
    }
    var head =
        sql.fetchOne("select * from finance_deferred_settlement where id = ?", id);
    if (head == null) throw notFound("延迟结算台账行 " + id + " 不存在或无权查看");
    var fromStatus = nz(head.get("status", String.class));
    if (DeferredSettlementStatus.valueOf(fromStatus).terminal()) {
      throw badRequest("延迟结算台账行 " + id + " 已是终态（" + fromStatus + "），不可再改");
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_deferred_settlement set status = 'VOID', updated_at = ?::timestamptz where id = ?",
        now,
        id);
    appendEvent(actor.tenantId(), id, "VOIDED", fromStatus, "VOID", input.reason().trim(), actor.userId(), now);
    return row(
        sql.fetchOne(
            "select d.*, s.settlement_no as settlement_no from finance_deferred_settlement d"
                + " left join finance_settlement s on s.tenant_id = d.tenant_id and s.id = d.settlement_id"
                + " where d.id = ?",
            id));
  }

  // ================================================================ 事件

  private void appendEvent(
      UUID tenantId,
      UUID deferredId,
      String type,
      String fromStatus,
      String toStatus,
      String reason,
      UUID actorId,
      OffsetDateTime at) {
    sql.execute(
        "insert into finance_deferred_settlement_event"
            + "(id,tenant_id,deferred_settlement_id,event_type,from_status,to_status,reason,actor_id,occurred_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        deferredId,
        type,
        fromStatus,
        toStatus,
        reason == null ? "" : reason,
        actorId,
        at);
  }

  // ================================================================ 映射

  private FinanceDeferredSettlementRow row(Record r) {
    var mode = DeferredSettlementMode.valueOf(r.get("settlement_mode", String.class));
    var status = DeferredSettlementStatus.valueOf(r.get("status", String.class));
    return new FinanceDeferredSettlementRow(
        r.get("id", UUID.class),
        r.get("rule_id", UUID.class),
        r.get("platform_order_id", UUID.class),
        r.get("sales_order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("external_order_no", String.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("currency", String.class),
        r.get("order_amount", BigDecimal.class),
        r.get("settled_amount", BigDecimal.class),
        r.get("unsettled_amount", BigDecimal.class),
        r.get("shipped_at", OffsetDateTime.class),
        r.get("shipment_status", String.class),
        mode,
        mode.label(),
        r.get("cycle_days", Integer.class),
        r.get("payout_lag_days", Integer.class),
        r.get("settlement_cycle_start", OffsetDateTime.class),
        r.get("settlement_cycle_end", OffsetDateTime.class),
        r.get("expected_arrived_at", OffsetDateTime.class),
        r.get("condition_note", String.class),
        status,
        status.label(),
        r.get("settlement_id", UUID.class),
        r.get("settlement_no", String.class),
        r.get("settled_settlement_count", Integer.class),
        r.get("source", String.class),
        r.get("scanned_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceDeferredSettlementRuleRow ruleRow(Record r) {
    var mode = DeferredSettlementMode.valueOf(r.get("settlement_mode", String.class));
    return new FinanceDeferredSettlementRuleRow(
        r.get("id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("shop_id", UUID.class),
        mode,
        mode.label(),
        r.get("cycle_days", Integer.class),
        r.get("payout_lag_days", Integer.class),
        r.get("currency", String.class),
        r.get("condition_note", String.class),
        r.get("status", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 工具

  /** 告警有上限：超了只报条数，否则一屏刷不出问题。 */
  private void warn(List<String> warnings, String message) {
    if (warnings.size() < MAX_WARNINGS) {
      warnings.add(message);
    } else if (warnings.size() == MAX_WARNINGS) {
      warnings.add("（告警过多，后续只报条数）");
    }
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static ResponseStatusException notFound(String detail) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, detail);
  }

  private static ResponseStatusException badRequest(String detail) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    var out = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) out[i] = binds.get(i);
    System.arraycopy(tail, 0, out, binds.size(), tail.length);
    return out;
  }
}
