package com.starcon.trade;

import static com.starcon.trade.OrderReviewRuleModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.2.1.2 审单规则：规则维护、买家画像、规则判定与命中链。
 *
 * <p><b>七条实现纪律</b>：
 *
 * <ol>
 *   <li><b>命中链是三层，逐层可复现</b>：判定结论 → 被考虑到的每一条规则（含未命中）→
 *       每个条件命中的订单字段与实际值。蓝图加粗句「客服被买家投诉时能说出『为什么这单被拦了』」
 *       只能由第三层兑现。
 *   <li><b>每条查询都带 tenant_id 谓词</b>：除 RLS 之外再显式写一遍
 *       {@code nullif(current_setting('app.current_tenant',true),'')::uuid}。命中明细会把
 *       订单金额、买家邮箱、地址异常原样写进库里，漏掉租户谓词就是带 PII 的跨租户泄露。
 *   <li><b>强制转人工压过一切自动通过</b>：{@code forced_manual} 的效力高于 priority，
 *       priority=999 的自动通过规则也压不住 priority=1 的强制转人工规则。
 *   <li><b>无规则命中 = 转人工</b>，不给隐含默认值（与 9.2.1.1「批量通过必须基于明确的规则」
 *       同一立场）。
 *   <li><b>取不到值就是取不到</b>：买家未登记画像时等级是 null 而不是兜一个 STANDARD ——
 *       兜底会让「VIP 自动通过」把全网买家都放过去。
 *   <li><b>时间一律显式绑定</b>：timestamptz 用 {@code ?::timestamptz} 绑定
 *       {@link OffsetDateTime}，绝不用 {@code Timestamp.valueOf(LocalDateTime...)}
 *       （那会静默偏 −28800s）。判定时刻与生效期比较必须是同一时区口径。
 *   <li><b>金额全程 BigDecimal</b>：阈值、订单金额、折扣比例都是 numeric(19,4)，
 *       比例按「百分数」存（85 = 85%），不做浮点运算。
 * </ol>
 */
@Service
public class OrderReviewRuleService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单条规则最多几个条件（与 DB 约束、服务层校验同口径）。 */
  static final int MAX_CONDITIONS = 20;

  /** 单个集合最多几个取值（国家码 / 店铺源都不可能超过这个数）。 */
  static final int MAX_VALUE_SET = 100;

  private static final int QTY_SCALE = 6;
  private static final int MONEY_SCALE = 4;

  /** 买家身份键分隔符：不可见 unit separator（U+001F），理由见 V144 文件头。 */
  private static final String SEP_CHAR = "\u001f";

  private static final String TENANT = " nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 纯邮编（同一个数字重复，如 00000 / 11111）是占位邮编，不是真实地址。 */
  private static final Pattern REPEATED_DIGITS = Pattern.compile("^(\\d)\\1+$");

  /** 信箱类地址：P.O. BOX / PO BOX / POST OFFICE BOX。 */
  private static final Pattern PO_BOX = Pattern.compile("(?i)\\bP\\.?\\s?O\\.?\\s?(BOX|BOXES)\\b|POST\\s+OFFICE\\s+BOX");

  private final DSLContext sql;

  public OrderReviewRuleService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 内部模型

  /** 订单侧真实列（判定输入）。所有字段都来自同服务同库的真实表。 */
  record OrderFacts(
      UUID orderId,
      String orderNo,
      String externalOrderNo,
      String orderSource,
      String currencyCode,
      BigDecimal grandTotal,
      BigDecimal discountAmount,
      BigDecimal subtotalAmount,
      BigDecimal discountRatePercent,
      String shippingCountry,
      String recipientName,
      String recipientPhone,
      String recipientEmail,
      String companyName,
      String city,
      String district,
      String postcode,
      String addressLine1,
      String buyerKey,
      String buyerLevel,
      Boolean blacklisted,
      String blacklistReason,
      List<String> skuCodes,
      int lineCount,
      BigDecimal totalQty,
      OffsetDateTime submittedAt,
      List<String> addressAnomalies) {}

  record ConditionDef(int seqNo, ConditionDimension dimension, ConditionOperator operator, List<String> valueSet,
      BigDecimal valueNumeric) {}

  record RuleDef(UUID id, String code, String name, int priority, ReviewAction action, boolean forcedManual,
      String matchMode, List<ConditionDef> conditions) {}

  /** 单个条件的判定结果 —— 加粗句要求的「哪个条件命中了哪个字段」。 */
  record ConditionOutcome(int seqNo, ConditionDimension dimension, ConditionOperator operator, String expected,
      String actualField, String actualValue, boolean satisfied, String explanation) {}

  record HitOutcome(UUID ruleId, String code, String name, int priority, ReviewAction action, boolean forcedManual,
      int evalRank, boolean matched, String reason, List<ConditionOutcome> conditions) {}

  record EvaluationOutcome(ReviewDecisionType decision, String decisionReason, HitOutcome winning,
      List<HitOutcome> hits, int matchedCount, int forcedCount) {}

  // ================================================================ 能力声明

  public OrderReviewRuleCapabilities capabilities() {
    UUID tenant = currentTenant();
    var ruleCounts = sql.fetchOne(
        "select count(*) as total,"
            + "count(*) filter (where status='ACTIVE') as active,"
            + "count(*) filter (where forced_manual) as forced from order_review_rule where tenant_id = ?",
        tenant);
    long profiles = sql.fetchOne("select count(*) from order_review_buyer_profile where tenant_id = ?", tenant)
        .get(0, Long.class);

    return new OrderReviewRuleCapabilities(
        List.of(
            new OrderReviewRuleCapabilities.OrderReviewDimension(
                "ORDER_FACTS", "订单事实（判定输入）", true,
                "9.1.2.2 / 9.1.1.1（trade 服务，已交付）→ 读 sales_order / sales_order_line / "
                    + "sales_order_party_snapshot / order_address_snapshot（均为 V99，同服务同库只读）",
                null),
            new OrderReviewRuleCapabilities.OrderReviewDimension(
                "SHOP_CONDITION", "店铺条件", false,
                "sales_order.order_source（V99）",
                "实查 V99：sales_order **没有 shop_code 列**，只有平台级的 order_source。"
                    + "所以 SHOP 维度当前只能按**平台**判定，同平台跨店铺区分不了 —— "
                    + "这一点如实报出而不是假装已支持店铺级条件。"
                    + "D5 平台单同步落地后由同步器补 shop_code，届时把条件取值改成店铺码即可，"
                    + "表结构与判定逻辑都不用动。"),
            new OrderReviewRuleCapabilities.OrderReviewDimension(
                "BUYER_PROFILE", "买家等级与黑名单", true,
                "租户自维护的 order_review_buyer_profile（V149 本表）",
                "15 客户（CRM 买家主数据）与 16 合规（黑名单）都未交付；按模块边界纪律 trade 也不能"
                    + "去读它们的库（sales_order_party_snapshot.customer_party_id 只是指向 catalog 的 id，"
                    + "拿不到等级）。所以这里落的是租户自维护的运营数据：运营给买家打标、拉黑。"
                    + "**未登记画像 = 等级未知（null），不是 STANDARD** —— 兜底会让「VIP 自动通过」"
                    + "把全网买家都放过去。15/16 落地后由同步器补 buyer_level 与合规黑名单，"
                    + "buyer_key 口径不变，规则条件不用改。"),
            new OrderReviewRuleCapabilities.OrderReviewDimension(
                "ADDRESS_ANOMALY", "地址异常判定", true,
                "order_address_snapshot（V99）实算", null),
            new OrderReviewRuleCapabilities.OrderReviewDimension(
                "DISCOUNT_ANOMALY", "异常折扣判定", true,
                "sales_order.discount_amount / subtotal_amount（V99）实算", null),
            new OrderReviewRuleCapabilities.OrderReviewDimension(
                "PENDING_QUEUE", "9.2.1.1 待审队列", false,
                "9.2.1.1 待审队列（trade 服务，未交付）",
                "本模块只负责**产出结论与命中链**，不负责推进订单状态。"
                    + "交接方式：9.2.1.1 按 order_id 读 order_review_rule_evaluation 最近一条 RECORDED 判定，"
                    + "并把命中链直接展示在待审列表的「命中规则」列上。"),
            new OrderReviewRuleCapabilities.OrderReviewDimension(
                "AUTOMATION_ENGINE", "18 自动化复用", false,
                "18 自动化（规则引擎复用，未交付）",
                "本模块的判定是**纯函数式的**：同一个订单事实 + 同一套规则 + 同一个判定时刻 → "
                    + "同一个结论与同一条命中链，没有隐含的外部状态。18 自动化要复用时，"
                    + "按 order_id 取订单事实、按 tenant 取生效规则、调同一条判定即可，"
                    + "不需要把本表结构搬走。")),
        List.of(ConditionDimension.values()),
        List.of(ConditionOperator.values()),
        List.of(ReviewAction.values()),
        List.of(ReviewRuleStatus.values()),
        List.of(ReviewDecisionType.values()),
        ruleCounts.get("total", Long.class),
        ruleCounts.get("active", Long.class),
        profiles,
        "判定口径三条：① **强制转人工压过一切自动通过**（forced_manual 的效力高于 priority）；"
            + "② **没有规则命中 = 转人工**，不给隐含默认值（与 9.2.1.1「批量通过必须基于明确的规则」"
            + "同一立场；想要「小额单默认放行」请写一条显式 AUTO_APPROVE 规则）；"
            + "③ 命中链按 priority DESC、rule_code ASC 排序逐条留痕，**未命中的规则与条件同样留痕** —— "
            + "只记命中项时「为什么这条规则没兜住」答不上来。",
        "下游缺口：9.2.1.1 待审队列（未交付，本模块只出结论与命中链）、"
            + "9.2.1.3 异常拦截（未交付，自动拒单不是本模块的动作）、"
            + "15 客户（买家等级主数据）、16 合规（黑名单主数据）、18 自动化（规则引擎复用）、"
            + "D5 平台单同步（shop_code）。本模块不假装已接通其中任何一个。");
  }

  // ================================================================ 规则维护

  public ReviewRulePage pageRules(ReviewRuleQuery query) {
    int size = pageSize(query == null ? 0 : query.size());
    int page = query == null || query.page() < 0 ? 0 : query.page();
    List<String> where = new ArrayList<>();
    where.add("r.tenant_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());

    if (query != null) {
      if (notBlank(query.keyword())) {
        where.add("(r.rule_code ilike '%' || ? || '%' or r.rule_name ilike '%' || ? || '%')");
        binds.add(query.keyword().trim());
        binds.add(query.keyword().trim());
      }
      if (notBlank(query.status())) {
        where.add("r.status = ?");
        binds.add(query.status().trim().toUpperCase(Locale.ROOT));
      }
      if (notBlank(query.action())) {
        where.add("r.action = ?");
        binds.add(query.action().trim().toUpperCase(Locale.ROOT));
      }
      if (query.forcedOnly() != null && query.forcedOnly()) {
        where.add("r.forced_manual");
      }
      if (query.effectiveOnly() != null && query.effectiveOnly()) {
        where.add("(r.effective_from <= now() and (r.effective_to is null or r.effective_to > now()))");
      }
      if (notBlank(query.dimension())) {
        // 维度筛选走 exists 子查询：条件在另一张表上，用 join 会让同一规则因多条条件重复出现。
        where.add("exists (select 1 from order_review_rule_condition c "
            + "where c.tenant_id = r.tenant_id and c.rule_id = r.id and c.dimension = ?)");
        binds.add(query.dimension().trim().toUpperCase(Locale.ROOT));
      }
    }
    String clause = " where " + String.join(" and ", where);

    // ⚠️ total **带** where 子句（它是「当前筛选命中多少条」）；
    // 而下面三个面板数从一条**不带** where 的查询里取 —— 它们是「本租户现在一共怎样」的数字，
    // 与当前这一页的筛选无关。混在一条查询里的话，切一下筛选框整个面板就跟着空掉。
    long total =
        sql.fetchOne("select count(*) c from order_review_rule r" + clause, binds.toArray())
            .get("c", Long.class);
    var panel = sql.fetchOne(
        "select count(*) filter (where status='ACTIVE') as active,"
            + "count(*) filter (where forced_manual) as forced,"
            + "count(*) filter (where effective_from <= now()"
            + " and (effective_to is null or effective_to > now())) as effective_now"
            + " from order_review_rule");

    List<ReviewRuleRow> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select r.id,r.rule_code,r.rule_name,r.description,r.priority,r.action,r.forced_manual,"
                + "r.match_mode,r.status,r.effective_from,r.effective_to,r.capability_note,r.note,"
                + "r.version,r.created_at,r.updated_at,"
                + "(select count(*) from order_review_rule_condition c "
                + " where c.tenant_id=r.tenant_id and c.rule_id=r.id) as condition_count,"
                + "(select string_agg(c.dimension, ',' order by c.seq_no) from order_review_rule_condition c "
                + " where c.tenant_id=r.tenant_id and c.rule_id=r.id) as dimension_summary"
                + " from order_review_rule r" + clause
                + " order by r.priority desc, r.rule_code asc limit ? offset ?",
            binds(size, page * size, binds))) {
      items.add(ruleRow(r));
    }
    return new ReviewRulePage(items, total, page, size,
        panel.get("active", Long.class), panel.get("forced", Long.class),
        panel.get("effective_now", Long.class));
  }

  public ReviewRuleDetail rule(UUID ruleId) {
    Record r = sql.fetchOne(
        "select id,rule_code,rule_name,description,priority,action,forced_manual,match_mode,status,"
            + "effective_from,effective_to,capability_note,note,version,created_at,updated_at,"
            + "(select count(*) from order_review_rule_condition c "
            + " where c.tenant_id=order_review_rule.tenant_id and c.rule_id=order_review_rule.id) "
            + "  as condition_count,"
            + "(select string_agg(c.dimension, ',' order by c.seq_no) "
            + " from order_review_rule_condition c "
            + " where c.tenant_id=order_review_rule.tenant_id and c.rule_id=order_review_rule.id) "
            + "  as dimension_summary "
            + "from order_review_rule where tenant_id = ? and id = ?",
        currentTenant(), ruleId);
    if (r == null) {
      throw notFound("规则不存在或不属于当前租户（RLS 下查不到即视为不存在）");
    }
    List<ReviewRuleCondition> conditions = conditionsOf(ruleId);
    var last = sql.fetchOne(
        "select id,order_id,order_no,order_source,currency_code,order_total,discount_rate_percent,mode,"
            + "evaluated_scope,decision,decision_reason,winning_rule_id,winning_rule_code,winning_rule_name,"
            + "considered_rule_count,matched_rule_count,forced_rule_count,address_anomaly_codes,"
            + "evaluated_at,evaluated_by_membership_id,created_at from order_review_rule_evaluation "
            + "where tenant_id = ? and winning_rule_id = ? order by evaluated_at desc limit 1",
        currentTenant(), ruleId);
    return new ReviewRuleDetail(ruleRow(r), conditions, last == null ? null : decision(last),
        "命中明细里 third 层（实际字段 + 实际值）是客服向买家解释的依据；"
            + "条件行是规则的**快照**：规则后来改了条件，历史命中行不受影响，"
            + "所以「当时按哪条规则判的」永远可复现。");
  }

  private List<ReviewRuleCondition> conditionsOf(UUID ruleId) {
    List<ReviewRuleCondition> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,seq_no,dimension,operator,value_set,value_numeric,description "
                + "from order_review_rule_condition where tenant_id = ? and rule_id = ? order by seq_no",
            currentTenant(), ruleId)) {
      out.add(conditionRow(r));
    }
    return out;
  }

  @Transactional
  public ReviewRuleSaved saveRule(ReviewRuleInput input, UUID actorId) {
    validateReviewRuleInput(input);
    UUID tenant = currentTenant();
    boolean creating = input.version() == null;
    UUID id;
    if (creating) {
      id = UUID.randomUUID();
      boolean taken = sql.fetchOne(
          "select 1 from order_review_rule where tenant_id = ? and rule_code = ?", tenant, input.ruleCode())
          != null;
      if (taken) {
        throw conflict("规则编号 " + input.ruleCode() + " 已存在。编号在租户内唯一，"
            + "它同时是同优先级时的排序依据 —— 改编号会让命中链的次序不可复现。");
      }
      sql.execute(
          "insert into order_review_rule(id,tenant_id,rule_code,rule_name,description,priority,action,"
              + "forced_manual,match_mode,status,effective_from,effective_to,capability_note,note,"
              + "created_by_membership_id,updated_by_membership_id) "
              + "values(?,?,?,?,?,?,?,?,?,'DRAFT',?::timestamptz,?::timestamptz,?,?,?,?)",
          id, tenant, input.ruleCode().trim(), input.ruleName().trim(), nz(input.description()),
          input.priority(), input.action().name(), Boolean.TRUE.equals(input.forcedManual()),
          matchMode(input.matchMode()), input.effectiveFrom(), input.effectiveTo(),
          nz(input.capabilityNote()), nz(input.note()), actorId, actorId);
    } else {
      id = inputRuleId(input);
      Record cur = sql.fetchOne(
          "select version from order_review_rule where tenant_id = ? and id = ?", tenant, id);
      if (cur == null) {
        throw notFound("规则不存在或不属于当前租户（RLS 下查不到即视为不存在）");
      }
      if (cur.get("version", Long.class) != input.version()) {
        throw conflict("规则已被他人改动（当前版本 " + cur.get("version", Long.class) + "，你提交的是 "
            + input.version() + "）。请刷新后重试，避免覆盖掉别人的改动。");
      }
      sql.execute(
          "update order_review_rule set rule_name=?,description=?,priority=?,action=?,forced_manual=?,"
              + "match_mode=?,effective_from=?::timestamptz,effective_to=?::timestamptz,capability_note=?,"
              + "note=?,updated_by_membership_id=?,updated_at=now(),version=version+1 "
              + "where tenant_id=? and id=?",
          input.ruleName().trim(), nz(input.description()), input.priority(), input.action().name(),
          Boolean.TRUE.equals(input.forcedManual()), matchMode(input.matchMode()),
          input.effectiveFrom(), input.effectiveTo(), nz(input.capabilityNote()), nz(input.note()),
          actorId, tenant, id);
      // 条件整体替换：删掉不再存在的、写上新增的、改掉改了的。
      // 刻意不做逐条 diff —— 「按序号 upsert 再删尾巴」在序号被重排时会留下旧条件的残影。
      sql.execute("delete from order_review_rule_condition where tenant_id = ? and rule_id = ?", tenant, id);
    }

    int n = 0;
    for (ReviewRuleConditionInput c : input.conditions()) {
      sql.execute(
          "insert into order_review_rule_condition(id,tenant_id,rule_id,seq_no,dimension,operator,"
              + "value_set,value_numeric,description) values(?,?,?,?,?,?,?::text[],?::numeric,?)",
          UUID.randomUUID(), tenant, id, c.seqNo(), c.dimension().name(), c.operator().name(),
          pgTextArray(normalizeValues(c)), c.valueNumeric(), nz(c.description()));
      n++;
    }
    return new ReviewRuleSaved(id, input.ruleCode().trim(), ReviewRuleStatus.DRAFT, n,
        creating ? "规则已建为草稿。草稿不参与判定 —— 用「试跑」拿真实订单验一遍再启用。"
            : "规则已更新。条件已整体替换（不在列表里的条件已被移除）。");
  }

  @Transactional
  public ReviewRuleSaved changeStatus(UUID ruleId, ReviewRuleStatusInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    UUID tenant = currentTenant();
    Record cur = sql.fetchOne(
        "select rule_code,status,version,effective_from,effective_to from order_review_rule "
            + "where tenant_id = ? and id = ?",
        tenant, ruleId);
    if (cur == null) {
      throw notFound("规则不存在或不属于当前租户（RLS 下查不到即视为不存在）");
    }
    if (input.version() != null && cur.get("version", Long.class) != input.version()) {
      throw conflict("规则已被他人改动（当前版本 " + cur.get("version", Long.class) + "）。请刷新后重试。");
    }
    ReviewRuleStatus from = ReviewRuleStatus.valueOf(cur.get("status", String.class));
    ReviewRuleStatus to = input.status();
    if (from == to) {
      throw conflict("规则已经是「" + to.label() + "」，本次调用没有改变任何状态");
    }
    if (from == ReviewRuleStatus.DISABLED && to == ReviewRuleStatus.DISABLED) {
      throw conflict("停用的规则不能再停用一次");
    }
    if (to == ReviewRuleStatus.DISABLED && !notBlank(input.note())) {
      throw bad("停用规则必须写备注：停用会改变之后所有订单的审单结论，"
          + "「当时为什么关掉这条规则」必须留下，否则事后无法解释一批订单为什么突然都转了人工。");
    }
    OffsetDateTime from_ = input.effectiveFrom() != null ? input.effectiveFrom()
        : cur.get("effective_from", OffsetDateTime.class);
    OffsetDateTime to_ = input.effectiveTo() != null ? input.effectiveTo()
        : cur.get("effective_to", OffsetDateTime.class);
    if (to_ != null && !to_.isAfter(from_)) {
      throw bad("生效止必须晚于生效起，否则这条规则永远不在生效期内");
    }
    sql.execute(
        "update order_review_rule set status=?,effective_from=?::timestamptz,effective_to=?::timestamptz,"
            + "note=?,updated_by_membership_id=?,updated_at=now(),version=version+1 "
            + "where tenant_id=? and id=?",
        to.name(), from_, to_,
        notBlank(input.note()) ? input.note().trim() : cur.get("rule_code", String.class),
        actorId, tenant, ruleId);
    return new ReviewRuleSaved(ruleId, cur.get("rule_code", String.class), to, conditionCount(tenant, ruleId),
        to == ReviewRuleStatus.ACTIVE
            ? "规则已启用，自本事务提交起参与判定。已生效期内的订单按新规则判定，"
                + "历史判定记录不受影响（命中明细存的是规则与条件的快照）。"
            : to == ReviewRuleStatus.DISABLED
                ? "规则已停用，之后的订单不再由它判定。已有判定记录仍然保留可查。"
                : "规则已退回草稿，不再参与判定。");
  }

  private int conditionCount(UUID tenant, UUID ruleId) {
    return sql.fetchOne(
        "select count(*) from order_review_rule_condition where tenant_id = ? and rule_id = ?",
        tenant, ruleId).get(0, Integer.class);
  }

  /** 更新时按编号反查规则 id：ReviewRuleInput 只带 ruleCode，id 从库里取。 */
  private UUID inputRuleId(ReviewRuleInput input) {
    Record r = sql.fetchOne(
        "select id from order_review_rule where tenant_id = ? and rule_code = ?",
        currentTenant(), input.ruleCode().trim());
    if (r == null) {
      throw notFound("规则不存在或不属于当前租户（RLS 下查不到即视为不存在）");
    }
    return r.get("id", UUID.class);
  }

  // ================================================================ 买家画像

  public BuyerProfilePage pageBuyerProfiles(BuyerProfileQuery query) {
    int size = pageSize(query == null ? 0 : query.size());
    int page = query == null || query.page() < 0 ? 0 : query.page();
    List<String> where = new ArrayList<>();
    where.add("tenant_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    if (query != null) {
      if (notBlank(query.keyword())) {
        where.add("(buyer_name ilike '%' || ? || '%' or buyer_email ilike '%' || ? || '%' "
            + "or buyer_phone ilike '%' || ? || '%')");
        binds.add(query.keyword().trim());
        binds.add(query.keyword().trim());
        binds.add(query.keyword().trim());
      }
      if (notBlank(query.buyerLevel())) {
        where.add("buyer_level = ?");
        binds.add(query.buyerLevel().trim().toUpperCase(Locale.ROOT));
      }
      if (query.blacklistedOnly() != null && query.blacklistedOnly()) {
        where.add("blacklisted");
      }
    }
    String clause = " where " + String.join(" and ", where);
    // total 带筛选（当前筛选命中多少条），面板数不带筛选（全租户一共多少人在黑名单）。
    long total = sql.fetchOne("select count(*) c from order_review_buyer_profile" + clause,
        binds.toArray()).get("c", Long.class);
    var totals = sql.fetchOne(
        "select count(*) filter (where blacklisted) as bl from order_review_buyer_profile");
    List<BuyerProfile> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,buyer_key,buyer_name,buyer_phone,buyer_email,buyer_level,blacklisted,"
                + "blacklist_reason,blacklisted_at,note,version,updated_at from order_review_buyer_profile"
                + clause + " order by blacklisted desc, buyer_level, updated_at desc limit ? offset ?",
            binds(size, page * size, binds))) {
      items.add(profileRow(r));
    }
    return new BuyerProfilePage(items, total, page, size, totals.get("bl", Long.class));
  }

  @Transactional
  public BuyerProfile saveBuyerProfile(BuyerProfileInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    String key = input.buyerKey().trim().toLowerCase(Locale.ROOT);
    if (!key.matches("^[0-9a-f]{32}$")) {
      throw bad("买家身份键必须是 32 位小写 md5。它由收货地址快照的姓名/电话/邮箱现算，"
          + "不由调用方自选 —— 两个模块各算各的会得出「同一个人/两个人」两种结论。");
    }
    String level = input.buyerLevel().trim().toUpperCase(Locale.ROOT);
    boolean blacklisted = Boolean.TRUE.equals(input.blacklisted());
    String reason = nz(input.blacklistReason()).trim();
    if (blacklisted && reason.isEmpty()) {
      throw bad("拉黑必须写理由。没有理由的黑名单，客服无法向买家解释 —— "
          + "那正是本模块要解决的问题（「为什么这单被拦了」）。");
    }
    OffsetDateTime blAt = blacklisted ? (input.blacklistedAt() != null ? input.blacklistedAt() : OffsetDateTime.now())
        : null;
    UUID tenant = currentTenant();
    Record cur = sql.fetchOne(
        "select id,version,blacklisted from order_review_buyer_profile where tenant_id = ? and buyer_key = ?",
        tenant, key);
    if (cur != null && input.version() != null && cur.get("version", Long.class) != input.version()) {
      throw conflict("该买家画像已被他人改动（当前版本 " + cur.get("version", Long.class) + "）。请刷新后重试。");
    }
    UUID id = cur == null ? UUID.randomUUID() : cur.get("id", UUID.class);
    boolean creating = cur == null;
    if (creating) {
      sql.execute(
          "insert into order_review_buyer_profile(id,tenant_id,buyer_key,buyer_name,buyer_phone,buyer_email,"
              + "buyer_level,blacklisted,blacklist_reason,blacklisted_at,note,updated_by_membership_id) "
              + "values(?,?,?,?,?,?,?,?,?,?::timestamptz,?,?)",
          id, tenant, key, nz(input.buyerName()).trim(), nz(input.buyerPhone()).trim(),
          nz(input.buyerEmail()).trim().toLowerCase(Locale.ROOT), level, blacklisted, reason, blAt,
          nz(input.note()), actorId);
    } else {
      sql.execute(
          "update order_review_buyer_profile set buyer_name=?,buyer_phone=?,buyer_email=?,buyer_level=?,"
              + "blacklisted=?,blacklist_reason=?,blacklisted_at=?::timestamptz,note=?,"
              + "updated_by_membership_id=?,updated_at=now(),version=version+1 where tenant_id=? and id=?",
          nz(input.buyerName()).trim(), nz(input.buyerPhone()).trim(),
          nz(input.buyerEmail()).trim().toLowerCase(Locale.ROOT), level, blacklisted, reason, blAt,
          nz(input.note()), actorId, tenant, id);
    }
    return new BuyerProfile(id, key, nz(input.buyerName()).trim(), nz(input.buyerPhone()).trim(),
        nz(input.buyerEmail()).trim().toLowerCase(Locale.ROOT), level, blacklisted, reason, blAt,
        nz(input.note()), creating ? 0 : cur.get("version", Long.class) + 1, OffsetDateTime.now());
  }

  // ================================================================ 判定

  /** 真实判定（RECORDED）：产出审单结论与命中链，落库成为规则生效记录。 */
  @Transactional
  public ReviewDetail review(UUID orderId, ReviewRunInput input, UUID actorId) {
    return runEvaluation(orderId, input, actorId, ReviewMode.RECORDED);
  }

  /** 试跑（DRY_RUN）：同样的判定、同样的命中链，但标成试跑，不冒充生产结论。 */
  @Transactional
  public ReviewDetail trial(UUID orderId, ReviewRunInput input, UUID actorId) {
    return runEvaluation(orderId, input, actorId, ReviewMode.DRY_RUN);
  }

  private ReviewDetail runEvaluation(
      UUID orderId, ReviewRunInput input, UUID actorId, ReviewMode mode) {
    if (input == null || input.evaluatedAt() == null) {
      throw bad("判定时刻不能为空：由调用方显式给出，不取服务器当前时间 —— "
          + "判定是「某时刻对某张单」的事实，用 now() 会让生效期判定随调用时间漂移");
    }
    OffsetDateTime at = input.evaluatedAt();
    UUID tenant = currentTenant();
    OrderFacts facts = loadOrderFacts(tenant, orderId);
    List<UUID> selected = input.ruleIds() == null ? List.of() : input.ruleIds().stream()
        .filter(java.util.Objects::nonNull).distinct().toList();
    EvaluationScope scope = selected.isEmpty() ? EvaluationScope.EFFECTIVE : EvaluationScope.SELECTED;
    List<RuleDef> rules = loadRules(tenant, at, selected);
    if (rules.isEmpty() && scope == EvaluationScope.SELECTED) {
      // 点名的规则一条都不存在 → 这是调用方的错误（跨租户 id、已删规则），必须报错。
      // 「一条规则都没有」在 EFFECTIVE 口径下不是错误，见下面 evaluate() 的处理。
      throw conflict("点名的规则一条都不存在或不属于当前租户（RLS 下查不到即视为不存在）");
    }
    // ⚠️ EFFECTIVE 口径下**零条生效规则不报错**，照常给出一个 MANUAL_REVIEW 结论。
    // 「该租户此刻没有生效规则」是真实且常见的运营状态（规则都过期了、还没配规则），
    // 报 409 会让 9.2.1.1 待审队列整批卡住 —— 而答案（转人工）是明确的、安全的。
    // 结论里会把「没有任何生效中的规则」与「有规则但都没命中」分成两句话，
    // 因为客服问的「为什么这单被拦了」在这两种情况下答案不同。
    EvaluationOutcome outcome = evaluate(facts, rules, at);

    UUID evaluationId = UUID.randomUUID();
    sql.execute(
        "insert into order_review_rule_evaluation(id,tenant_id,order_id,order_no,order_source,currency_code,"
            + "order_total,discount_rate_percent,mode,evaluated_scope,decision,decision_reason,"
            + "winning_rule_id,winning_rule_code,winning_rule_name,considered_rule_count,matched_rule_count,"
            + "forced_rule_count,address_anomaly_codes,evaluated_at,evaluated_by_membership_id) "
            + "values(?,?,?,?,?,?,?::numeric,?::numeric,?,?,?,?,?::uuid,?,?,?,?,?,"
            + "?::text[],?::timestamptz,?::uuid)",
        evaluationId, tenant, facts.orderId(), facts.orderNo(), facts.orderSource(), facts.currencyCode(),
        facts.grandTotal(), facts.discountRatePercent(), mode.name(), scope.name(),
        outcome.decision().name(), outcome.decisionReason(),
        outcome.winning() == null ? null : outcome.winning().ruleId(),
        outcome.winning() == null ? "" : outcome.winning().code(),
        outcome.winning() == null ? "" : outcome.winning().name(),
        outcome.hits().size(), outcome.matchedCount(), outcome.forcedCount(),
        pgTextArray(facts.addressAnomalies()), at, actorId);

    for (HitOutcome h : outcome.hits()) {
      UUID hitId = UUID.randomUUID();
      sql.execute(
          "insert into order_review_rule_hit(id,tenant_id,evaluation_id,rule_id,rule_code,rule_name,"
              + "priority,action,forced_manual,eval_rank,matched,reason) values(?,?,?,?::uuid,?,?,?,?,?,?,?,?)",
          hitId, tenant, evaluationId, h.ruleId(), h.code(), h.name(), h.priority(), h.action().name(),
          h.forcedManual(), h.evalRank(), h.matched(), h.reason());
      for (ConditionOutcome c : h.conditions()) {
        sql.execute(
            "insert into order_review_rule_hit_condition(id,tenant_id,hit_id,seq_no,dimension,operator,"
                + "expected,actual_field,actual_value,satisfied,explanation) "
                + "values(?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), tenant, hitId, c.seqNo(), c.dimension().name(), c.operator().name(),
            c.expected(), c.actualField(), c.actualValue(), c.satisfied(), c.explanation());
      }
    }

    Record head = sql.fetchOne(
        "select id,order_id,order_no,order_source,currency_code,order_total,discount_rate_percent,mode,"
            + "evaluated_scope,decision,decision_reason,winning_rule_id,winning_rule_code,winning_rule_name,"
            + "considered_rule_count,matched_rule_count,forced_rule_count,address_anomaly_codes,"
            + "evaluated_at,evaluated_by_membership_id,created_at from order_review_rule_evaluation "
            + "where tenant_id = ? and id = ?",
        tenant, evaluationId);
    return new ReviewDetail(decision(head), hitsOf(evaluationId), factsSummary(facts),
        "结论「" + outcome.decision().label() + "」由 " + outcome.hits().size() + " 条参与判定的规则得出，"
            + "其中 " + outcome.matchedCount() + " 条命中、"
            + (outcome.forcedCount() > 0 ? outcome.forcedCount() + " 条强制转人工。" : "无强制转人工规则命中。")
            + " 展开命中链可以逐条看到「哪个条件、拿订单的哪个字段、实际值多少」。");
  }

  public EvaluationPage pageEvaluations(EvaluationQuery query) {
    int size = pageSize(query == null ? 0 : query.size());
    int page = query == null || query.page() < 0 ? 0 : query.page();
    List<String> where = new ArrayList<>();
    where.add("e.tenant_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    if (query != null) {
      if (notBlank(query.keyword())) {
        where.add("(e.order_no ilike '%' || ? || '%' or o.external_order_no ilike '%' || ? || '%')");
        binds.add(query.keyword().trim());
        binds.add(query.keyword().trim());
      }
      if (query.orderId() != null) {
        where.add("e.order_id = ?");
        binds.add(query.orderId());
      }
      if (notBlank(query.decision())) {
        where.add("e.decision = ?");
        binds.add(query.decision().trim().toUpperCase(Locale.ROOT));
      }
      if (notBlank(query.mode())) {
        where.add("e.mode = ?");
        binds.add(query.mode().trim().toUpperCase(Locale.ROOT));
      }
      if (query.forcedOnly() != null && query.forcedOnly()) {
        where.add("e.forced_rule_count > 0");
      }
    }
    String clause = " where " + String.join(" and ", where);
    // 同上：total 带筛选，四个面板数不带。
    long total = sql.fetchOne(
        "select count(*) c from order_review_rule_evaluation e"
            + " left join sales_order o on o.tenant_id = e.tenant_id and o.id = e.order_id" + clause,
        binds.toArray()).get("c", Long.class);
    var totals = sql.fetchOne(
        "select count(*) filter (where e.decision='AUTO_APPROVE') as auto_ok,"
            + "count(*) filter (where e.decision='MANUAL_REVIEW') as manual,"
            + "count(*) filter (where e.forced_rule_count>0) as forced,"
            + "count(*) filter (where e.winning_rule_id is null) as no_rule"
            + " from order_review_rule_evaluation e");
    List<ReviewDecision> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select e.id,e.order_id,e.order_no,e.order_source,e.currency_code,e.order_total,"
                + "e.discount_rate_percent,e.mode,e.evaluated_scope,e.decision,e.decision_reason,"
                + "e.winning_rule_id,e.winning_rule_code,e.winning_rule_name,e.considered_rule_count,"
                + "e.matched_rule_count,e.forced_rule_count,e.address_anomaly_codes,e.evaluated_at,"
                + "e.evaluated_by_membership_id,e.created_at from order_review_rule_evaluation e"
                + " left join sales_order o on o.tenant_id = e.tenant_id and o.id = e.order_id" + clause
                + " order by e.evaluated_at desc, e.id limit ? offset ?",
            binds(size, page * size, binds))) {
      items.add(decision(r));
    }
    return new EvaluationPage(items, total, page, size,
        totals.get("auto_ok", Long.class), totals.get("manual", Long.class),
        totals.get("forced", Long.class), totals.get("no_rule", Long.class));
  }

  public ReviewDetail evaluation(UUID evaluationId) {
    UUID tenant = currentTenant();
    Record head = sql.fetchOne(
        "select id,order_id,order_no,order_source,currency_code,order_total,discount_rate_percent,mode,"
            + "evaluated_scope,decision,decision_reason,winning_rule_id,winning_rule_code,winning_rule_name,"
            + "considered_rule_count,matched_rule_count,forced_rule_count,address_anomaly_codes,"
            + "evaluated_at,evaluated_by_membership_id,created_at from order_review_rule_evaluation "
            + "where tenant_id = ? and id = ?",
        tenant, evaluationId);
    if (head == null) {
      throw notFound("判定记录不存在或不属于当前租户（RLS 下查不到即视为不存在）");
    }
    OrderFacts facts = loadOrderFacts(tenant, head.get("order_id", UUID.class));
    return new ReviewDetail(decision(head), hitsOf(evaluationId), factsSummary(facts),
        "这是 append-only 的判定留痕：判定一经落库就不可改写（DB 层 REVOKE + 触发器双重挡住）。"
            + "结论变了就重新判定一次（新增一行），而不是改旧的那一行 —— "
            + "客服向买家解释的依据必须能追溯到某一次具体的判定。");
  }

  // ================================================================ 判定引擎

  /**
   * 判定本体：纯函数式，没有外部状态。
   *
   * <p>同一组订单事实 + 同一套规则 + 同一个判定时刻 → 同一个结论与同一条命中链。
   * 这也是 18 自动化将来能复用它的前提。
   */
  EvaluationOutcome evaluate(OrderFacts facts, List<RuleDef> rules, OffsetDateTime at) {
    List<HitOutcome> hits = new ArrayList<>();
    int rank = 0;
    int matched = 0;
    int forced = 0;
    for (RuleDef rule : rules) {
      rank++;
      List<ConditionOutcome> outcomes = new ArrayList<>();
      int satisfiedCount = 0;
      for (ConditionDef c : rule.conditions()) {
        ConditionOutcome o = judgeCondition(facts, c);
        outcomes.add(o);
        if (o.satisfied()) {
          satisfiedCount++;
        }
      }
      boolean ruleMatched = rule.matchMode().equals("ANY") ? satisfiedCount > 0
          : (!rule.conditions().isEmpty() && satisfiedCount == rule.conditions().size());
      String reason;
      if (ruleMatched) {
        reason = "命中：" + (rule.matchMode().equals("ANY")
            ? "任一条件满足（" + satisfiedCount + "/" + rule.conditions().size() + " 个条件满足）"
            : "全部 " + rule.conditions().size() + " 个条件满足");
        matched++;
        if (rule.forcedManual()) {
          forced++;
        }
      } else {
        List<ConditionOutcome> failed = outcomes.stream().filter(o -> !o.satisfied()).toList();
        reason = "未命中："
            + (failed.isEmpty() ? "没有条件" : failed.stream().map(ConditionOutcome::explanation)
                .reduce((a, b) -> a + "；" + b).orElse("条件未全部满足"));
      }
      hits.add(new HitOutcome(rule.id(), rule.code(), rule.name(), rule.priority(), rule.action(),
          rule.forcedManual(), rank, ruleMatched, reason, outcomes));
    }

    // 强制转人工压过一切自动通过：先找强制命中的，再找普通转人工，最后才轮到自动通过。
    HitOutcome winning = hits.stream().filter(HitOutcome::matched).filter(HitOutcome::forcedManual).findFirst()
        .or(() -> hits.stream().filter(HitOutcome::matched)
            .filter(h -> h.action() == ReviewAction.MANUAL_REVIEW).findFirst())
        .or(() -> hits.stream().filter(HitOutcome::matched)
            .filter(h -> h.action() == ReviewAction.AUTO_APPROVE).findFirst())
        .orElse(null);

    ReviewDecisionType decision;
    String reason;
    if (winning != null) {
      if (winning.forcedManual() && winning.action() == ReviewAction.AUTO_APPROVE) {
        // 防御：强制位与动作位同时挂在自动通过上的矛盾配置。
        decision = ReviewDecisionType.MANUAL_REVIEW;
        reason = "规则 " + winning.code() + "（" + winning.name() + "）被标记为强制转人工，"
            + "即使它的动作是自动通过也强制拦下 —— 强制位效力高于动作位。";
      } else if (winning.forcedManual()) {
        decision = ReviewDecisionType.MANUAL_REVIEW;
        reason = "命中强制转人工规则 " + winning.code() + "（" + winning.name() + "）：" + winning.reason();
      } else {
        decision = winning.action() == ReviewAction.MANUAL_REVIEW
            ? ReviewDecisionType.MANUAL_REVIEW : ReviewDecisionType.AUTO_APPROVE;
        reason = "按规则 " + winning.code() + "（" + winning.name() + "）判定为"
            + (decision == ReviewDecisionType.AUTO_APPROVE ? "自动通过" : "转人工") + "：" + winning.reason()
            + (forced > 0 ? "。另有 " + forced + " 条强制转人工规则命中。" : "。");
      }
    } else {
      // 没有规则命中 → 转人工，不给隐含默认值。
      // 「零条生效规则」与「有规则但都没命中」分成两句话：客服问「为什么这单被拦了」
      // 时这两种情况的答案完全不同，合成一句就答不出来。
      decision = ReviewDecisionType.MANUAL_REVIEW;
      reason = hits.isEmpty()
          ? at + " 没有任何生效中的规则（生效期 " + at + " 这一刻一条都不在）→ 默认转人工。"
              + "审单不给隐含默认值：自动通过必须由某条规则明确给出。"
          : at + " 没有任何规则命中（参与判定 " + hits.size() + " 条）→ 默认转人工。"
              + "审单不给隐含默认值：自动通过必须由某条规则明确给出。";
    }
    return new EvaluationOutcome(decision, reason, winning, hits, matched, forced);
  }

  /** 单个条件判定 —— 每一支都必须产出「订单的哪个字段、实际值多少、人话解释」。 */
  private ConditionOutcome judgeCondition(OrderFacts f, ConditionDef c) {
    // 标记型（IS_TRUE / IS_FALSE）没有取值集合，「期望值」就是运算符本身 ——
    // 命中明细的 expected 列照样有一句人话，不会是空串。
    String expected = c.operator().label();
    boolean satisfied;
    String actualField;
    String actualValue;
    String explanation;
    switch (c.dimension()) {
      case SHOP -> {
        actualField = "sales_order.order_source";
        actualValue = f.orderSource();
        expected = String.join(", ", c.valueSet());
        boolean in = containsIgnoreCase(c.valueSet(), f.orderSource());
        satisfied = c.operator() == ConditionOperator.IN ? in : !in;
        explanation = "订单来源 " + nz(f.orderSource()) + " " + c.operator().label() + " [" + expected + "] → "
            + (satisfied ? "满足" : "不满足")
            + "（来源是平台级，店铺级条件待 D5 平台单同步补 shop_code 后可用）";
      }
      case AMOUNT -> {
        actualField = "sales_order.grand_total_amount";
        actualValue = plain(f.grandTotal());
        expected = plain(c.valueNumeric());
        satisfied = compareNumbers(f.grandTotal(), c.valueNumeric(), c.operator());
        explanation = "订单总额 " + actualValue + " " + c.operator().label() + " 阈值 " + expected + " → "
            + (satisfied ? "满足" : "不满足");
      }
      case DISCOUNT_RATIO -> {
        actualField = "sales_order.discount_amount / subtotal_amount";
        actualValue = f.discountRatePercent() == null ? "" : plain(f.discountRatePercent()) + "%";
        expected = plain(c.valueNumeric()) + "%";
        if (f.discountRatePercent() == null) {
          // 小计为 0 时比例不可计算：null 不是 0，0 会被读成「零元订单打了 0 折」。
          satisfied = false;
          explanation = "订单小计为 0，折扣比例不可计算（不是 0），"
              + c.operator().label() + " " + expected + " 的条件无法判定 → 不满足";
        } else {
          satisfied = compareNumbers(f.discountRatePercent(), c.valueNumeric(), c.operator());
          explanation = "订单折扣率 " + actualValue + "（折扣 " + plain(f.discountAmount())
              + " / 小计 " + plain(f.subtotalAmount()) + "）" + c.operator().label() + " " + expected
              + " → " + (satisfied ? "满足" : "不满足");
        }
      }
      case SHIPPING_COUNTRY -> {
        actualField = "order_address_snapshot.country_code";
        actualValue = f.shippingCountry();
        expected = String.join(", ", c.valueSet());
        boolean in = containsIgnoreCase(c.valueSet(), f.shippingCountry());
        satisfied = c.operator() == ConditionOperator.IN ? in : !in;
        explanation = "收货国家 " + nz(f.shippingCountry()) + " " + c.operator().label() + " [" + expected
            + "] → " + (satisfied ? "满足" : "不满足");
      }
      case BUYER_LEVEL -> {
        actualField = "order_review_buyer_profile.buyer_level";
        actualValue = f.buyerLevel() == null ? "" : f.buyerLevel();
        expected = String.join(", ", c.valueSet());
        if (f.buyerLevel() == null) {
          // ⚠️ 等级未知时两个方向都判不满足：乐观地认为「不在 VIP 集合里」会让
          // 「非 VIP 自动通过」把全网买家都放过去。
          satisfied = false;
          explanation = "买家 " + nz(f.recipientName()) + "（" + nz(f.recipientEmail()) + "）"
              + "**未登记等级档案**，等级未知（不是 STANDARD，也不做任何乐观推断）→ 不满足";
        } else {
          boolean in = containsIgnoreCase(c.valueSet(), f.buyerLevel());
          satisfied = c.operator() == ConditionOperator.IN ? in : !in;
          explanation = "买家 " + nz(f.recipientName()) + " 等级 " + f.buyerLevel() + " "
              + c.operator().label() + " [" + expected + "] → " + (satisfied ? "满足" : "不满足");
        }
      }
      case BUYER_BLACKLIST -> {
        actualField = "order_review_buyer_profile.blacklisted";
        actualValue = f.blacklisted() == null ? "" : String.valueOf(f.blacklisted());
        boolean want = c.operator() == ConditionOperator.IS_TRUE;
        satisfied = f.blacklisted() != null && f.blacklisted() == want;
        if (f.blacklisted() == null) {
          explanation = "买家 " + nz(f.recipientName()) + " 未登记画像，是否在黑名单未知"
              + "（不是「不在黑名单」）→ 不满足";
        } else {
          explanation = "买家 " + nz(f.recipientName()) + "（" + nz(f.recipientEmail()) + "）"
              + (f.blacklisted() ? "在黑名单里" : "不在黑名单")
              + (f.blacklisted() && f.blacklistReason() != null && !f.blacklistReason().isEmpty()
                  ? "，理由：" + f.blacklistReason() : "")
              + "，条件要求「" + c.operator().label() + "」→ " + (satisfied ? "满足" : "不满足");
        }
      }
      case ITEM -> {
        actualField = "sales_order_line.sku_code";
        actualValue = String.join(", ", f.skuCodes());
        expected = String.join(", ", c.valueSet());
        List<String> hit = f.skuCodes().stream().filter(s -> containsIgnoreCase(c.valueSet(), s)).toList();
        boolean any = !hit.isEmpty();
        satisfied = c.operator() == ConditionOperator.IN ? any : !any;
        explanation = "订单含 " + f.skuCodes().size() + " 个 SKU，"
            + (any ? "其中 [" + String.join(", ", hit) + "] " : "没有任何 SKU ")
            + c.operator().label() + " [" + expected + "] → " + (satisfied ? "满足" : "不满足");
      }
      case ADDRESS_ANOMALY -> {
        actualField = "order_address_snapshot（地址异常实算）";
        actualValue = String.join(", ", f.addressAnomalies());
        expected = String.join(", ", c.valueSet());
        List<String> hit = f.addressAnomalies().stream()
            .filter(a -> containsIgnoreCase(c.valueSet(), a)).toList();
        boolean any = !hit.isEmpty();
        satisfied = c.operator() == ConditionOperator.IN ? any : !any;
        explanation = "收货地址检出 " + (f.addressAnomalies().isEmpty() ? "无异常" : f.addressAnomalies().size()
                + " 种异常 [" + String.join(", ", f.addressAnomalies()) + "]")
            + (any ? "，其中 [" + String.join(", ", hit) + "] " : "") + c.operator().label() + " ["
            + expected + "] → " + (satisfied ? "满足" : "不满足");
      }
      default -> throw new IllegalStateException("未覆盖的条件维度：" + c.dimension());
    }
    return new ConditionOutcome(c.seqNo(), c.dimension(), c.operator(), expected, actualField,
        actualValue, satisfied, explanation);
  }

  // ================================================================ 订单事实

  /** 订单事实：一条真 SQL 联查，不是内存里凑出来的对象。 */
  private OrderFacts loadOrderFacts(UUID tenant, UUID orderId) {
    Record head = sql.fetchOne(
        "select o.id,o.order_no,o.external_order_no,o.order_source,o.currency,o.grand_total_amount,"
            + "o.discount_amount,o.subtotal_amount,o.submitted_at,"
            + "a.country_code,a.recipient_name,a.recipient_phone,a.recipient_email,a.company_name,"
            + "a.city,a.district,a.postcode,a.address_line1,"
            + "(select count(*) from sales_order_line l where l.tenant_id=o.tenant_id and l.order_id=o.id) "
            + "  as line_count,"
            + "(select coalesce(sum(l.quantity),0) from sales_order_line l "
            + "  where l.tenant_id=o.tenant_id and l.order_id=o.id) as total_qty,"
            + "(select string_agg(l.sku_code, ',' order by l.line_no) from sales_order_line l "
            + "  where l.tenant_id=o.tenant_id and l.order_id=o.id) as sku_codes "
            + "from sales_order o "
            + "left join order_address_snapshot a on a.tenant_id=o.tenant_id and a.order_id=o.id "
            + " and a.address_role='SHIPPING' "
            + "where o.tenant_id = " + TENANT + " and o.id = ?",
        orderId);
    if (head == null) {
      throw notFound("订单不存在或不属于当前租户（RLS 下查不到即视为不存在）。"
          + "审单只对**本租户**的订单生效，跨租户订单 id 一律按不存在处理。");
    }
    BigDecimal subtotal = money(head.get("subtotal_amount", BigDecimal.class));
    BigDecimal discount = money(head.get("discount_amount", BigDecimal.class));
    BigDecimal total = money(head.get("grand_total_amount", BigDecimal.class));
    BigDecimal rate = discountRate(discount, subtotal);

    String name = head.get("recipient_name", String.class);
    String phone = head.get("recipient_phone", String.class);
    String email = head.get("recipient_email", String.class);
    String buyerKey = buyerKey(name, phone, email);
    List<String> skuCodes = splitList(head.get("sku_codes", String.class));

    // 买家画像：先按身份键查，再按邮箱反查（同一个人的姓名拼写可能不同）。
    Record profile = sql.fetchOne(
        "select buyer_level,blacklisted,blacklist_reason from order_review_buyer_profile "
            + "where tenant_id = ? and (buyer_key = ? or lower(buyer_email) = lower(?)) "
            + "order by (buyer_key = ?) desc limit 1",
        tenant, buyerKey, nz(email), buyerKey);

    String company = head.get("company_name", String.class);
    String city = head.get("city", String.class);
    String district = head.get("district", String.class);
    String postcode = head.get("postcode", String.class);
    String line1 = head.get("address_line1", String.class);
    List<String> anomalies = addressAnomalies(postcode, phone, company, city, district, line1);

    return new OrderFacts(head.get("id", UUID.class), head.get("order_no", String.class),
        head.get("external_order_no", String.class), head.get("order_source", String.class),
        head.get("currency", String.class), total, discount, subtotal, rate,
        head.get("country_code", String.class), name, phone, email, company, city, district,
        postcode, line1, buyerKey,
        profile == null ? null : profile.get("buyer_level", String.class),
        profile == null ? null : profile.get("blacklisted", Boolean.class),
        profile == null ? null : profile.get("blacklist_reason", String.class),
        skuCodes, head.get("line_count", Integer.class), qty(head.get("total_qty", BigDecimal.class)),
        head.get("submitted_at", OffsetDateTime.class), anomalies);
  }

  /**
   * 地址异常实算：全部由收货地址快照的列推出，不查任何外部服务。
   *
   * <p>异常码词表（与 V149 注释一致）：MISSING_POSTCODE / MISSING_PHONE / PO_BOX_ONLY /
   * INCOMPLETE_ADDRESS / RESERVED_POSTCODE。
   */
  static List<String> addressAnomalies(
      String postcode, String phone, String company, String city, String district, String line1) {
    List<String> out = new ArrayList<>();
    if (!notBlank(postcode)) {
      out.add("MISSING_POSTCODE");
    }
    if (!notBlank(phone)) {
      out.add("MISSING_PHONE");
    }
    if (!notBlank(company) && notBlank(line1) && PO_BOX.matcher(line1).find()) {
      out.add("PO_BOX_ONLY");
    }
    if (!notBlank(line1) || line1.length() < 6 || (!notBlank(city) && !notBlank(district))) {
      out.add("INCOMPLETE_ADDRESS");
    }
    if (notBlank(postcode) && REPEATED_DIGITS.matcher(postcode.trim()).matches()) {
      out.add("RESERVED_POSTCODE");
    }
    return out;
  }

  /** 买家身份键：与 V144 合单的地址分支同一公式（U+001F 分隔，不可见字符）。 */
  static String buyerKey(String name, String phone, String email) {
    try {
      var digest = java.security.MessageDigest.getInstance("MD5");
      var bytes = digest.digest(
          (nz(name) + SEP_CHAR + nz(phone) + SEP_CHAR + nz(email))
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var sb = new StringBuilder();
      for (byte b : bytes) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 折扣率百分数：小计为 0 时是 null 而不是 0。 */
  static BigDecimal discountRate(BigDecimal discount, BigDecimal subtotal) {
    if (subtotal == null || subtotal.signum() == 0) {
      return null;
    }
    return discount.multiply(new BigDecimal("100")).divide(subtotal, MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 生效规则：按 priority DESC, rule_code ASC —— 命中链的次序由它决定，不靠运气。 */
  private List<RuleDef> loadRules(UUID tenant, OffsetDateTime at, List<UUID> selected) {
    List<RuleDef> out = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    String where;
    if (selected.isEmpty()) {
      where = "where tenant_id = ? and status = 'ACTIVE' "
          + "and effective_from <= ?::timestamptz "
          + "and (effective_to is null or effective_to > ?::timestamptz)";
      binds.add(at);
      binds.add(at);
    } else {
      where = "where tenant_id = ? and id = any(?::uuid[])";
      binds.add(pgUuidArray(selected));
    }
    for (Record r :
        sql.fetch("select id,rule_code,rule_name,priority,action,forced_manual,match_mode from order_review_rule "
            + where + " order by priority desc, rule_code asc",
            binds.toArray())) {
      UUID id = r.get("id", UUID.class);
      List<ConditionDef> conditions = new ArrayList<>();
      for (Record c :
          sql.fetch(
              "select seq_no,dimension,operator,value_set,value_numeric from order_review_rule_condition "
                  + "where tenant_id = ? and rule_id = ? order by seq_no",
              tenant, id)) {
        conditions.add(new ConditionDef(c.get("seq_no", Integer.class),
            ConditionDimension.valueOf(c.get("dimension", String.class)),
            ConditionOperator.valueOf(c.get("operator", String.class)),
            splitList(pgArrayToLiteral(c.get("value_set", String[].class))),
            c.get("value_numeric", BigDecimal.class)));
      }
      out.add(new RuleDef(id, r.get("rule_code", String.class), r.get("rule_name", String.class),
          r.get("priority", Integer.class), ReviewAction.valueOf(r.get("action", String.class)),
          r.get("forced_manual", Boolean.class), r.get("match_mode", String.class), conditions));
    }
    return out;
  }

  private List<RuleHit> hitsOf(UUID evaluationId) {
    UUID tenant = currentTenant();
    Map<String, RuleHit> byId = new LinkedHashMap<>();
    Map<String, List<HitCondition>> conds = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,rule_id,rule_code,rule_name,priority,action,forced_manual,eval_rank,matched,reason "
                + "from order_review_rule_hit where tenant_id = ? and evaluation_id = ? order by eval_rank",
            tenant, evaluationId)) {
      String id = r.get("id", UUID.class).toString();
      conds.put(id, new ArrayList<>());
      byId.put(id, new RuleHit(r.get("id", UUID.class), r.get("rule_id", UUID.class),
          r.get("rule_code", String.class), r.get("rule_name", String.class),
          r.get("priority", Integer.class),
          ReviewAction.valueOf(r.get("action", String.class)),
          ReviewAction.valueOf(r.get("action", String.class)).label(),
          r.get("forced_manual", Boolean.class), r.get("eval_rank", Integer.class),
          r.get("matched", Boolean.class), r.get("reason", String.class), new ArrayList<>()));
    }
    for (Record r :
        sql.fetch(
            "select c.hit_id,c.seq_no,c.dimension,c.operator,c.expected,c.actual_field,c.actual_value,"
                + "c.satisfied,c.explanation from order_review_rule_hit_condition c "
                + "join order_review_rule_hit h on h.tenant_id = c.tenant_id and h.id = c.hit_id "
                + "where c.tenant_id = ? and h.evaluation_id = ? order by c.hit_id, c.seq_no",
            tenant, evaluationId)) {
      conds.get(r.get("hit_id", UUID.class).toString()).add(new HitCondition(
          r.get("seq_no", Integer.class),
          ConditionDimension.valueOf(r.get("dimension", String.class)),
          ConditionDimension.valueOf(r.get("dimension", String.class)).label(),
          ConditionOperator.valueOf(r.get("operator", String.class)), r.get("expected", String.class),
          r.get("actual_field", String.class), r.get("actual_value", String.class),
          r.get("satisfied", Boolean.class), r.get("explanation", String.class)));
    }
    List<RuleHit> out = new ArrayList<>();
    for (var e : byId.entrySet()) {
      RuleHit base = e.getValue();
      out.add(new RuleHit(base.id(), base.ruleId(), base.ruleCode(), base.ruleName(), base.priority(),
          base.action(), base.actionLabel(), base.forcedManual(), base.evalRank(), base.matched(),
          base.reason(), conds.get(e.getKey())));
    }
    return out;
  }

  private OrderFactsSummary factsSummary(OrderFacts f) {
    return new OrderFactsSummary(f.orderId(), f.orderNo(), f.externalOrderNo(), f.orderSource(),
        f.currencyCode(), f.grandTotal(), f.discountAmount(), f.subtotalAmount(), f.discountRatePercent(),
        f.shippingCountry(), f.recipientName(), f.recipientPhone(), f.recipientEmail(), f.buyerKey(),
        f.buyerLevel(), f.blacklisted(), f.addressAnomalies(), f.skuCodes(), f.lineCount(),
        f.totalQty(), f.submittedAt());
  }

  // ================================================================ 校验

  private void validateReviewRuleInput(ReviewRuleInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    if (input.effectiveTo() != null && !input.effectiveTo().isAfter(input.effectiveFrom())) {
      throw bad("生效止必须晚于生效起，否则这条规则永远不在生效期内 —— "
          + "配了却永远不生效，是最难查的一类故障");
    }
    String mode = matchMode(input.matchMode());
    if (!"ALL".equals(mode) && !"ANY".equals(mode)) {
      throw bad("条件组合方式只能是 ALL（全部满足）或 ANY（任一满足）");
    }
    List<ReviewRuleConditionInput> conditions = input.conditions();
    if (conditions == null || conditions.isEmpty()) {
      throw bad("至少要有一个条件。零条件的规则要么命中每一张单（ANY），要么永不命中（ALL）——"
          + "两种都不是可用的规则（ACTIVE 状态下 DB 也会再挡一次）");
    }
    if (conditions.size() > MAX_CONDITIONS) {
      throw bad("一条规则最多 " + MAX_CONDITIONS + " 个条件，当前 " + conditions.size() + " 个");
    }
    Set<Integer> seqs = new LinkedHashSet<>();
    for (ReviewRuleConditionInput c : conditions) {
      if (c == null || c.seqNo() == null || c.dimension() == null || c.operator() == null) {
        throw bad("每个条件都要有 seqNo、dimension 与 operator");
      }
      if (!seqs.add(c.seqNo())) {
        throw bad("条件序号 " + c.seqNo() + " 重复。序号是命中明细的排序依据，重复会让解释顺序不可复现");
      }
      if (!c.operator().fits(c.dimension())) {
        throw bad("运算符「" + c.operator().label() + "」不能配维度「" + c.dimension().label() + "」。"
            + "规则：金额与折扣比例用数值型（大于等于/大于/小于等于/小于/等于）、"
            + "黑名单用标记型（为真/为假）、其余维度用集合型（属于/不属于）。"
            + "配错的话规则看上去配好了，却永远判不出结果");
      }
      List<String> values = normalizeValues(c);
      if (c.operator().isSetBased() && (values == null || values.isEmpty())) {
        throw bad("「" + c.operator().label() + "」需要至少一个取值。"
            + "空集合的「不属于」会命中每一张单 —— 那是一条静默放行所有订单的规则");
      }
      if (values != null && values.size() > MAX_VALUE_SET) {
        throw bad("单个条件的取值最多 " + MAX_VALUE_SET + " 个，当前 " + values.size() + " 个");
      }
      if (c.operator().isNumeric() && c.valueNumeric() == null) {
        throw bad("「" + c.operator().label() + "」需要数值阈值");
      }
      if (c.operator().isNumeric() && c.valueNumeric() != null && c.valueNumeric().signum() < 0
          && (c.dimension() == ConditionDimension.DISCOUNT_RATIO
              || c.dimension() == ConditionDimension.AMOUNT)) {
        throw bad("金额与折扣比例的阈值不能为负：负阈值会让这条规则命中每一张单");
      }
      if (c.operator().isNumeric() && c.valueNumeric() != null
          && c.dimension() == ConditionDimension.DISCOUNT_RATIO
          && c.valueNumeric().compareTo(new BigDecimal("100")) > 0) {
        throw bad("折扣比例阈值最多 100（百分数口径，100 = 全免），当前 " + plain(c.valueNumeric()));
      }
    }
  }

  /** 取值统一：去空白、转大写、去空、保持录入顺序（顺序会原样出现在命中明细里）。 */
  private static List<String> normalizeValues(ReviewRuleConditionInput c) {
    List<String> out = new ArrayList<>();
    if (c.valueSet() == null) {
      return out;
    }
    for (String v : c.valueSet()) {
      if (v == null) {
        continue;
      }
      String t = v.trim().toUpperCase(Locale.ROOT);
      if (t.isEmpty()) {
        continue;
      }
      if (!out.contains(t)) {
        out.add(t);
      }
    }
    return out;
  }

  private static String matchMode(String raw) {
    return raw == null || raw.isBlank() ? "ALL" : raw.trim().toUpperCase(Locale.ROOT);
  }

  // ================================================================ 行映射

  private ReviewRuleRow ruleRow(Record r) {
    OffsetDateTime from = r.get("effective_from", OffsetDateTime.class);
    OffsetDateTime to = r.get("effective_to", OffsetDateTime.class);
    ReviewAction action = ReviewAction.valueOf(r.get("action", String.class));
    ReviewRuleStatus status = ReviewRuleStatus.valueOf(r.get("status", String.class));
    return new ReviewRuleRow(r.get("id", UUID.class), r.get("rule_code", String.class),
        r.get("rule_name", String.class), r.get("description", String.class),
        r.get("priority", Integer.class), action, action.label(),
        Boolean.TRUE.equals(r.get("forced_manual", Boolean.class)), r.get("match_mode", String.class),
        status, status.label(), from, to,
        from != null && !from.isAfter(OffsetDateTime.now()) && (to == null || to.isAfter(OffsetDateTime.now())),
        r.get("condition_count", Integer.class), nz(r.get("dimension_summary", String.class)),
        r.get("capability_note", String.class), r.get("note", String.class),
        r.get("version", Long.class), r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private ReviewRuleCondition conditionRow(Record r) {
    ConditionDimension dim = ConditionDimension.valueOf(r.get("dimension", String.class));
    ConditionOperator op = ConditionOperator.valueOf(r.get("operator", String.class));
    return new ReviewRuleCondition(r.get("id", UUID.class), r.get("seq_no", Integer.class), dim, dim.label(),
        op, op.label(), splitList(pgArrayToLiteral(r.get("value_set", String[].class))),
        r.get("value_numeric", BigDecimal.class), r.get("description", String.class));
  }

  private BuyerProfile profileRow(Record r) {
    return new BuyerProfile(r.get("id", UUID.class), r.get("buyer_key", String.class),
        r.get("buyer_name", String.class), r.get("buyer_phone", String.class),
        r.get("buyer_email", String.class), r.get("buyer_level", String.class),
        Boolean.TRUE.equals(r.get("blacklisted", Boolean.class)),
        r.get("blacklist_reason", String.class), r.get("blacklisted_at", OffsetDateTime.class),
        r.get("note", String.class), r.get("version", Long.class), r.get("updated_at", OffsetDateTime.class));
  }

  private ReviewDecision decision(Record r) {
    ReviewDecisionType d = ReviewDecisionType.valueOf(r.get("decision", String.class));
    ReviewMode m = ReviewMode.valueOf(r.get("mode", String.class));
    return new ReviewDecision(r.get("id", UUID.class), r.get("order_id", UUID.class),
        r.get("order_no", String.class), r.get("order_source", String.class),
        r.get("currency_code", String.class), r.get("order_total", BigDecimal.class),
        r.get("discount_rate_percent", BigDecimal.class), m, m.label(),
        r.get("evaluated_scope", String.class), d, d.label(), r.get("decision_reason", String.class),
        r.get("winning_rule_id", UUID.class), r.get("winning_rule_code", String.class),
        r.get("winning_rule_name", String.class), r.get("considered_rule_count", Integer.class),
        r.get("matched_rule_count", Integer.class), r.get("forced_rule_count", Integer.class),
        splitList(pgArrayToLiteral(r.get("address_anomaly_codes", String[].class))),
        r.get("evaluated_at", OffsetDateTime.class), r.get("evaluated_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 小工具

  private static Object[] binds(int size, int offset, List<Object> head) {
    List<Object> all = new ArrayList<>(head);
    all.add(size);
    all.add(offset);
    return all.toArray();
  }

  private static int pageSize(int requested) {
    return Math.min(MAX_PAGE_SIZE, Math.max(1, requested <= 0 ? 50 : requested));
  }

  /** PostgreSQL 数组字面量（只用于值都来自受控词表的列）。 */
  private static String pgTextArray(List<String> values) {
    if (values == null || values.isEmpty()) {
      return "{}";
    }
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }
    return sb.append('}').toString();
  }

  private static String pgUuidArray(List<UUID> values) {
    return pgTextArray(values.stream().map(UUID::toString).toList());
  }

  /** 数组列读回来后统一按逗号切；数组内容本身不含逗号（词表受控）。 */
  private static String pgArrayToLiteral(String[] values) {
    return values == null || values.length == 0 ? null : String.join(",", values);
  }

  private static List<String> splitList(String csv) {
    if (!notBlank(csv)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (String part : csv.split(",")) {
      String t = part.trim();
      if (!t.isEmpty()) {
        out.add(t);
      }
    }
    return out;
  }

  private static boolean containsIgnoreCase(List<String> values, String target) {
    if (target == null) {
      return false;
    }
    for (String v : values) {
      if (v != null && v.trim().equalsIgnoreCase(target.trim())) {
        return true;
      }
    }
    return false;
  }

  private static boolean compareNumbers(BigDecimal actual, BigDecimal threshold, ConditionOperator op) {
    if (actual == null || threshold == null) {
      return false;
    }
    int c = actual.compareTo(threshold);
    return switch (op) {
      case GTE -> c >= 0;
      case GT -> c > 0;
      case LTE -> c <= 0;
      case LT -> c < 0;
      case EQ -> c == 0;
      default -> false;
    };
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal qty(BigDecimal value) {
    return value == null ? null : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  private static String plain(BigDecimal value) {
    return value == null ? "" : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
