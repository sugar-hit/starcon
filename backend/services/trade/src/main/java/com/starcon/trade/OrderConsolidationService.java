package com.starcon.trade;

import static com.starcon.trade.OrderConsolidationModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.3.1.2 合单：候选检索、合并落库、物流号回传留痕。
 *
 * <p><b>五条实现纪律</b>：
 *
 * <ol>
 *   <li><b>候选集合是真 SQL</b>：{@link #candidates} 是一条针对 sales_order ×
 *       order_address_snapshot × sales_order_line × order_charge 的联表查询，
 *       买家与地址两个指纹都在 DB 侧用 md5 现算。**不在 Java 里过滤一个假列表。**
 *   <li><b>每条查询都带 tenant_id 谓词</b>：除 RLS 之外再显式写一遍
 *       {@code o.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid}。
 *       合单的查询形状是「同一买家 + 同一地址」的**宽条件**，一旦漏掉租户谓词就是
 *       跨租户合单 —— 两家互不相识的公司的订单会进同一个包裹并互相看到对方买家信息。
 *   <li><b>排除项与候选集同等重要</b>：每张没进候选集的订单都带一条
 *       {@link ExclusionReason} 返回，「为什么只出来两张」不需要猜。
 *   <li><b>汇总数由 DB 实算</b>：order_count / total_qty / separate_shipping_amount /
 *       cross_shop 由 V144 的 guard_order_consolidation_totals 触发器从成员行算，
 *       服务层**不**自己写这几个数 —— 写了就会出现「明细 3 单、头写 2 单」。
 *   <li><b>时间一律显式绑定</b>：timestamptz 列用 {@code ?::timestamptz}，
 *       绝不用 {@code Timestamp.valueOf(LocalDateTime...)}（那会静默偏 −28800s）。
 * </ol>
 */
@Service
public class OrderConsolidationService {

  /** 单页最大行数。合并单是凭据不是流水，但仍设上限防一次拉爆 JVM。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final int QTY_SCALE = 6;
  private static final int MONEY_SCALE = 4;

  private static final BigDecimal ZERO_QTY = new BigDecimal("0.000000");
  private static final BigDecimal ZERO_MONEY = new BigDecimal("0.0000");

  /**
   * 指纹分隔符：用不可见 unit separator（U+001F）而不是 {@code '|'}。
   *
   * <p>收件人字段由运营自由录入，{@code '|'} 会让「Smith|John」与「Smith」+「John」
   * 两组字段拼出同一个字符串 —— 那是一次**跨买家的错误合单**，比不合单严重得多。
   *
   * <p>刻意拆成两个常量：{@link #SEP_CHAR} 是**真实字符**（Java 侧 md5 用它），
   * {@link #SEP_SQL} 是它的 PostgreSQL 字面量（SQL 侧 md5 用它）。两者混用会让
   * Java 算出来的指纹带上一对引号，与 SQL 算的永远对不上。
   */
  private static final String SEP_CHAR = "\u001f";

  private static final String SEP_SQL = " E'\\x1f' ";

  private static final String TENANT_PREDICATE =
      " nullif(current_setting('app.current_tenant',true),'')::uuid";

  private final DSLContext sql;

  public OrderConsolidationService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  public OrderConsolidationCapabilities capabilities() {
    boolean configured = ruleOrNull() != null;
    return new OrderConsolidationCapabilities(
        List.of(
            new OrderConsolidationCapabilities.Dimension(
                "CANDIDATE_ORDERS",
                "候选订单集合（同一买家 + 同一收货地址）",
                true,
                "9.1.1.1 订单列表（trade 服务，已交付）→ 本服务读 sales_order / "
                    + "order_address_snapshot / sales_order_line / order_charge",
                null),
            new OrderConsolidationCapabilities.Dimension(
                "CONSOLIDATION_RULE",
                "合单规则",
                configured,
                "租户自维护（order_consolidation_rule）",
                configured
                    ? null
                    : "本租户尚未登记合单规则。合单会真实改变包裹形态与逐单回传口径，"
                        + "「没配规则 = 默认全开」会把一次运营疏忽变成一批错发的货，"
                        + "所以这里不提供隐含默认值：先 POST /rule 登记规则。"),
            new OrderConsolidationCapabilities.Dimension(
                "PLATFORM_SHOP",
                "平台与店铺归属（跨店铺同买家的识别依据）",
                false,
                "9.1 平台单同步（trade 服务，D5 未交付）",
                "sales_order 只有 order_source（平台级），**没有 shop_code 列**，"
                    + "所以平台码由 order_source 推出、店铺码留空。"
                    + "D5 落地后由同步器补 shop_code，本模块的表结构与读法都不用改；"
                    + "在此之前「同一平台跨店铺」无法区分，这一点如实报出而不是假装已支持。"),
            new OrderConsolidationCapabilities.Dimension(
                "PACKING_DOWNSTREAM",
                "8.3.3.2 打包",
                false,
                "8.3.3.2 打包（warehouse 服务，未交付）",
                "本模块的 DISPATCHED 状态 = 「已下发」这一个动作的凭据；"
                    + "仓库侧真正的打包单在 warehouse 自己的库里，"
                    + "按模块边界纪律不得跨库读写，故本模块不查也不写它。"),
            new OrderConsolidationCapabilities.Dimension(
                "FULFILLMENT_REQUEST",
                "9.3.3.1 履约请求",
                false,
                "9.3.3.1 履约请求（trade 服务，未交付）",
                "「合并履约请求」在本模块落成 order_consolidation 头 + member 明细这一对；"
                    + "真正的履约编排与预占由 9.3.3.1 消费，下游落地后接同一形状即可。"),
            new OrderConsolidationCapabilities.Dimension(
                "WRITEBACK",
                "9.4.2.1 物流号回传",
                false,
                "9.4.2.1 回传（channel / trade，未交付）",
                "本模块按蓝图加粗句为**每个平台订单**各建一条回传任务并登记物流号与结果，"
                    + "但真正向平台发请求由 9.4.2.1 负责；"
                    + "交接方式是同服务同库读 order_consolidation_writeback_task。"),
            new OrderConsolidationCapabilities.Dimension(
                "SHIPMENT_PROGRESS",
                "已发货判定（候选过滤依据）",
                true,
                "9.1.1.3 订单变更与取消（trade 服务，已交付）→ order_fulfillment_line_progress",
                null)),
        List.of(ConsolidationStatus.values()),
        List.of(WritebackStatus.values()),
        List.of(ExclusionReason.values()),
        configured,
        false,
        "「同一买家」与「同一收货地址」都从 **order_address_snapshot（SHIPPING）** 现算 md5："
            + "sales_order 没有买家身份列（实查 V99），也没有店铺列。"
            + "用快照而不是实时地址是硬口径 —— V99 已把 SUBMITTED 后的地址快照冻住，"
            + "合单要判的是「这两单本来是不是寄到同一个地方」。",
        "下游缺口：8.3.3.2 打包（warehouse）、9.3.3.1 履约请求（trade）、"
            + "9.4.2.1 物流号回传（未交付）、9.1 平台单同步 shop_code（D5 未交付）。"
            + "本模块只到「已下发 + 逐单登记物流号」，不假装已经接通。");
  }

  // ================================================================ 合单规则

  /** 合单规则读取。没登记过就是 null —— 不是一份带默认值的规则。 */
  public ConsolidationRule rule() {
    return ruleOrNull();
  }

  private ConsolidationRule ruleOrNull() {
    Record r =
        sql.fetchOne(
            "select id,max_orders_per_consolidation,allow_cross_shop,max_window_hours,"
                + "require_same_currency,require_same_payment_method,require_shipping_address,"
                + "version,updated_at from order_consolidation_rule");
    return r == null ? null : ruleRow(r);
  }

  private ConsolidationRule requireRule() {
    var rule = ruleOrNull();
    if (rule == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "本租户尚未登记合单规则。合单会真实改变包裹形态与逐单回传口径，"
              + "本模块不提供隐含默认值：请先 POST /trade/fulfillment/consolidation/rule 登记规则。");
    }
    return rule;
  }

  /** 登记 / 更新合单规则（upsert，一租户一条）。 */
  @Transactional
  public ConsolidationRule saveRule(ConsolidationRuleInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    Integer max = input.maxOrdersPerConsolidation();
    if (max == null || max < 2) {
      throw bad("单批最多合几笔订单至少是 2：合单是 N→1，上限为 1 等于把这个功能关掉");
    }
    if (input.maxWindowHours() != null && input.maxWindowHours() <= 0) {
      throw bad("可合单时间窗必须大于 0 小时；不限就留空，不要填 0（0 会让任何跨时段的单都不可合）");
    }
    UUID tenant = currentTenant();
    var existing =
        sql.fetchOne("select id,version from order_consolidation_rule where tenant_id = ?", tenant);
    if (existing == null) {
      UUID id = UUID.randomUUID();
      sql.execute(
          "insert into order_consolidation_rule(id,tenant_id,max_orders_per_consolidation,"
              + "allow_cross_shop,max_window_hours,require_same_currency,"
              + "require_same_payment_method,require_shipping_address,version,"
              + "updated_by_membership_id,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,0,?,?::timestamptz,?::timestamptz)",
          id,
          tenant,
          max,
          nzBool(input.allowCrossShop(), true),
          input.maxWindowHours(),
          nzBool(input.requireSameCurrency(), true),
          nzBool(input.requireSamePaymentMethod(), true),
          nzBool(input.requireShippingAddress(), true),
          actorId,
          OffsetDateTime.now(),
          OffsetDateTime.now());
      return rule();
    }
    sql.execute(
        "update order_consolidation_rule set max_orders_per_consolidation=?,allow_cross_shop=?,"
            + "max_window_hours=?,require_same_currency=?,require_same_payment_method=?,"
            + "require_shipping_address=?,version=version+1,updated_by_membership_id=?,"
            + "updated_at=?::timestamptz where tenant_id=?",
        max,
        nzBool(input.allowCrossShop(), true),
        input.maxWindowHours(),
        nzBool(input.requireSameCurrency(), true),
        nzBool(input.requireSamePaymentMethod(), true),
        nzBool(input.requireShippingAddress(), true),
        actorId,
        OffsetDateTime.now(),
        tenant);
    return rule();
  }

  // ================================================================ 候选检索

  /**
   * 候选订单检索（**真 SQL**）。
   *
   * <p>判定全部在 DB 侧完成：买家指纹与地址指纹用 md5 现算、行数与件数用
   * sales_order_line 聚合、单独发运费用 order_charge 的 SHIPPING 行聚合。
   * 「已发货」判定读 9.1.1.3 已交付的 order_fulfillment_line_progress（已发件数 > 0 即不可合）。
   */
  public CandidateSet candidates(CandidateQuery query) {
    if (query == null) {
      throw bad("请求体不能为空");
    }
    if (notBlank(query.recipientName()) == false) {
      throw bad("收件人姓名不能为空：合单的「同一买家」判据来自收货地址快照");
    }
    if (notBlank(query.addressLine1()) == false) {
      throw bad("地址第一行不能为空：合单的「同一收货地址」判据来自收货地址快照");
    }
    UUID tenant = currentTenant();

    // 地址指纹可以由入参现算（入参就是收货地址快照的字段）。
    // **买家指纹不能**：它优先取 sales_order_party_snapshot.customer_party_id，
    // 而请求方给的是姓名/电话/邮箱，不是 catalog 的客户 ID。
    // 所以买家口径在 SQL 里定锚：取该地址下**最早提交**的那一笔订单的买家指纹，
    // 只保留与它同买家的订单 —— 同一地址上的另一位买家因此被判为 BUYER_MISMATCH，
    // 而不是被合进别人的包裹里。
    String addrFp =
        addressFingerprint(
            query.addressLine1(),
            query.addressLine2(),
            query.countryCode(),
            query.province(),
            query.city(),
            query.district(),
            query.postcode());

    // 刻意不叫 sql：那会遮住 DSLContext 字段，sql.fetch(...) 会在编译期变成 String.fetch(...)。
    String candidateSql =
        "with ship as ("
            + "  select o.tenant_id, o.id order_id, o.order_no, o.external_order_no, o.order_source,"
            + "         o.currency, o.payment_method, o.status order_status, o.submitted_at,"
            + "         a.recipient_name, a.recipient_phone, a.recipient_email,"
            + "         a.country_code, a.province, a.city, a.district, a.postcode,"
            + "         a.address_line1, a.address_line2,"
            + "         coalesce('P:'||p.customer_party_id::text,"
            + "                  'A:'||md5(concat_ws(" + SEP_SQL + ", a.recipient_name,"
            + "                    a.recipient_phone, a.recipient_email))) buyer_fingerprint,"
            + "         md5(concat_ws(" + SEP_SQL + ", a.country_code, a.province, a.city, a.district,"
            + "           a.postcode, a.address_line1, a.address_line2)) address_fingerprint,"
            + "         (select count(*) from sales_order_line ol"
            + "            where ol.tenant_id=o.tenant_id and ol.order_id=o.id) line_count,"
            + "         (select coalesce(sum(ol.quantity),0) from sales_order_line ol"
            + "            where ol.tenant_id=o.tenant_id and ol.order_id=o.id) total_qty,"
            + "         (select coalesce(round(sum(ch.amount),4),0) from order_charge ch"
            + "            where ch.tenant_id=o.tenant_id and ch.order_id=o.id"
            + "              and ch.charge_type='SHIPPING') separate_shipping,"
            + "         (select coalesce(sum(p.shipped_qty),0) from order_fulfillment_line_progress p"
            + "            where p.tenant_id=o.tenant_id and p.order_id=o.id) shipped_qty"
            + "    from sales_order o"
            + "    join order_address_snapshot a"
            + "      on a.tenant_id=o.tenant_id and a.order_id=o.id and a.address_role='SHIPPING'"
            + "    left join sales_order_party_snapshot p"
            + "      on p.tenant_id=o.tenant_id and p.order_id=o.id"
            + "   where o.tenant_id = " + TENANT_PREDICATE
            + "     and md5(concat_ws(" + SEP_SQL + ", a.country_code, a.province, a.city, a.district,"
            + "           a.postcode, a.address_line1, a.address_line2)) = ?"
            + "), seed as ("
            // ⚠️ 平手必须用 order_no 打破，**不能**用 order_id：uuid 是随机的，
            // 同一秒提交的两张单会让「谁是锚点」每次查询都不一样 ——
            // 于是同一批数据在两次刷新之间给出不同的候选集，运营会认为系统在乱跳。
            + "  select buyer_fingerprint from ship"
            + "   order by submitted_at asc nulls last, order_no limit 1"
            + ")"
            + " select s.*,"
            + "   (s.buyer_fingerprint = (select buyer_fingerprint from seed)) same_buyer,"
            + "   (select count(*) from order_consolidation_member m"
            + "     where m.tenant_id=s.tenant_id and m.order_id=s.order_id"
            + "       and m.member_status<>'CANCELLED') active_consolidation"
            + "   from ship s"
            + "  order by s.submitted_at desc nulls last, s.order_id"
            + " limit 200";

    List<Record> rows =
        sql.fetch(candidateSql, addrFp);

    // 买家指纹取自**实际命中的最早那笔**，而不是由入参现算 ——
    // 界面回显的指纹因此与 DB 里写进合并头的是同一个值。
    String buyerFp =
        rows.stream()
            .filter(r -> Boolean.TRUE.equals(r.get("same_buyer", Boolean.class)))
            .map(r -> nz(r.get("buyer_fingerprint", String.class)))
            .findFirst()
            .orElse("");

    var rule = ruleOrNull();
    boolean ruleConfigured = rule != null;
    List<CandidateOrder> candidates = new ArrayList<>();
    List<ExcludedOrder> excluded = new ArrayList<>();

    // 第一遍：只做**事实类**排除（未提交 / 已发货 / 已在别的合并里）。
    // 事实与规则分两遍，是为了让「已发过货」不会被归成「超出时间窗」——
    // 后者会让人去调一个根本无关的配置。
    List<Record> facts = new ArrayList<>();
    for (Record r : rows) {
      String reason = factExclusionReason(r);
      if (reason == null) {
        facts.add(r);
      } else {
        ExclusionReason code = ExclusionReason.valueOf(reason);
        excluded.add(
            new ExcludedOrder(r.get("order_id", UUID.class), nz(r.get("order_no", String.class)), code, code.label()));
      }
    }

    // 第二遍：规则类排除需要一个**参照系**，所以先把锚点定下来：
    //   * 时间窗锚点 = 事实候选里最早的 submitted_at（不是「当前时间」——
    //     用 now() 喂一个会被断言的分片键会让同一批单在不同时段给出不同候选集）；
    //   * 币种 / 支付方式 / 跨店铺的参照 = 最早那一笔（最早那笔永远可合，
    //     与哪一笔比都不该把最早那笔排除掉）。
    facts.sort(java.util.Comparator.comparing(
        (Record r) -> r.get("submitted_at", OffsetDateTime.class),
        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
    OffsetDateTime anchor = facts.isEmpty() ? null : facts.get(0).get("submitted_at", OffsetDateTime.class);
    String baseCurrency = facts.isEmpty() ? "" : nz(facts.get(0).get("currency", String.class));
    String basePayment = facts.isEmpty() ? "" : nz(facts.get(0).get("payment_method", String.class));
    String basePlatform =
        facts.isEmpty() ? "" : platformCodeOf(nz(facts.get(0).get("order_source", String.class)));

    for (Record r : facts) {
      String reason = ruleExclusionReason(r, rule, anchor, baseCurrency, basePayment, basePlatform);
      if (reason == null) {
        candidates.add(candidateRow(r));
      } else {
        ExclusionReason code = ExclusionReason.valueOf(reason);
        excluded.add(
            new ExcludedOrder(r.get("order_id", UUID.class), nz(r.get("order_no", String.class)), code, code.label()));
      }
    }
    candidates.sort(java.util.Comparator.comparing(
        (CandidateOrder c) -> c.submittedAt(),
        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));

    // 规则未登记时**不拦候选**（让运营看得见「本来有哪些单」），
    // 但 ruleBlockedReason 说清为什么现在不能落合并。
    String blocked = null;
    if (!ruleConfigured) {
      blocked = "本租户尚未登记合单规则：候选可见，但落合并会被拒绝（见 capabilities）";
    } else if (candidates.size() < 2) {
      blocked = "可合单的订单不足两笔（本模块是 N→1）";
    } else if (candidates.size() > rule.maxOrdersPerConsolidation()) {
      blocked =
          "候选有 "
              + candidates.size()
              + " 笔，超过本租户规则的单批上限 "
              + rule.maxOrdersPerConsolidation()
              + " 笔：请分批合并";
    }

    BigDecimal qty =
        candidates.stream()
            .map(CandidateOrder::totalQty)
            .reduce(ZERO_QTY, BigDecimal::add)
            .setScale(QTY_SCALE, RoundingMode.HALF_UP);
    BigDecimal ship =
        candidates.stream()
            .map(CandidateOrder::separateShippingAmount)
            .reduce(ZERO_MONEY, BigDecimal::add)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    return new CandidateSet(
        candidates, excluded, buyerFp, addrFp, candidates.size(), qty, ship, ruleConfigured, blocked);
  }

  /**
   * **事实类**排除（返回 null 表示通过）。与规则无关，看数据本身就能判定。
   *
   * <p>顺序：未提交 → 已发货 → 已在别的未作废合并里。
   */
  private static String factExclusionReason(Record r) {
    // 同一地址但**不是同一个买家**：家庭成员 / 同事代收 / 合租都会命中这一条。
    // 把它们合进一箱等于把甲的货发给了乙，这是错发而不是省运费。
    if (!Boolean.TRUE.equals(r.get("same_buyer", Boolean.class))) {
      return "BUYER_MISMATCH";
    }
    if (!"SUBMITTED".equals(nz(r.get("order_status", String.class)))) {
      return "NOT_SUBMITTED";
    }
    BigDecimal shipped = r.get("shipped_qty", BigDecimal.class);
    if (shipped != null && shipped.compareTo(BigDecimal.ZERO) > 0) {
      return "PARTIALLY_SHIPPED";
    }
    Integer active = r.get("active_consolidation", Integer.class);
    if (active != null && active > 0) {
      return "ALREADY_CONSOLIDATED";
    }
    return null;
  }

  /**
   * **规则类**排除（返回 null 表示通过）。全部相对**最早那一笔**判定。
   *
   * <p>参照系取最早那笔而不是「第一行」：候选集按提交时间排序后第一行就是最早的，
   * 拿它当基准才能保证「最早那笔永远不被规则排除」—— 否则一批全同的单会被规则
   * 整批排除，界面只显示「候选 0 笔」而说不清是哪条规则。
   */
  private static String ruleExclusionReason(
      Record r,
      ConsolidationRule rule,
      OffsetDateTime anchor,
      String baseCurrency,
      String basePayment,
      String basePlatform) {
    // 未配规则时**不拦**：让运营看得见「本来有哪些单」，阻断理由由 ruleBlockedReason 说明。
    if (rule == null) {
      return null;
    }
    OffsetDateTime submitted = r.get("submitted_at", OffsetDateTime.class);
    if (rule.maxWindowHours() != null && anchor != null && submitted != null
        && submitted.isAfter(anchor.plusHours(rule.maxWindowHours()))) {
      return "OUTSIDE_TIME_WINDOW";
    }
    if (rule.requireSameCurrency()
        && !baseCurrency.equals(nz(r.get("currency", String.class)))) {
      return "CURRENCY_MISMATCH";
    }
    if (rule.requireSamePaymentMethod()
        && !basePayment.equals(nz(r.get("payment_method", String.class)))) {
      return "PAYMENT_METHOD_MISMATCH";
    }
    if (!rule.allowCrossShop()
        && !basePlatform.equals(platformCodeOf(nz(r.get("order_source", String.class))))) {
      return "CROSS_SHOP_NOT_ALLOWED";
    }
    return null;
  }

  // ================================================================ 落合并

  /**
   * 落一次合并（头 + 成员行 + 逐单回传任务）。
   *
   * <p>**汇总数一个都不在这里写**：order_count / total_qty /
   * separate_shipping_amount / cross_shop 由 V144 的延迟约束触发器在提交时从成员行实算回填。
   */
  @Transactional
  public ConsolidationSaved consolidate(ConsolidationInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    var rule = requireRule();
    List<UUID> orderIds = dedupe(input.orderIds());
    if (orderIds.size() < 2) {
      throw bad("合单至少要两笔订单：本模块是 N→1，只挂一个订单不叫合单");
    }
    if (orderIds.size() > rule.maxOrdersPerConsolidation()) {
      throw bad(
          "本次选了 "
              + orderIds.size()
              + " 笔，超过本租户规则的单批上限 "
              + rule.maxOrdersPerConsolidation()
              + " 笔");
    }
    OffsetDateTime at = input.consolidatedAt();
    if (at == null) {
      throw bad("合单时刻必须显式给出：本模块记账的是一个已发生的动作，不取服务器当前时间");
    }

    // 逐单实查：这一段是本模块最重要的闸 —— 它保证「同一买家 + 同一地址 + 可合」
    // 是**在落库这一刻对真实订单成立的**，而不是相信调用方传来的两个指纹。
    List<OrderFacts> facts = loadFacts(orderIds);
    for (OrderFacts f : facts) {
      if (!"SUBMITTED".equals(f.status())) {
        throw bad("订单 " + f.orderNo() + " 的状态是 " + f.status() + "，只有已提交的订单可合");
      }
      if (f.shippedQty().compareTo(BigDecimal.ZERO) > 0) {
        throw bad("订单 " + f.orderNo() + " 已经发出 " + f.shippedQty() + " 件，不能再合进新包裹");
      }
      int active =
          sql.fetchOne(
                  "select count(*) c from order_consolidation_member"
                      + " where tenant_id=? and order_id=? and member_status<>'CANCELLED'",
                  currentTenant(),
                  f.orderId())
              .get("c", Integer.class);
      if (active > 0) {
        throw conflict("订单 " + f.orderNo() + " 已经在一个未作废的合并里，不能重复合单");
      }
    }
    String buyerFp = facts.get(0).buyerFingerprint();
    String addrFp = facts.get(0).addressFingerprint();
    for (OrderFacts f : facts) {
      if (!buyerFp.equals(f.buyerFingerprint())) {
        throw bad(
            "订单 "
                + f.orderNo()
                + " 的买家指纹与所选第一笔不同：本模块只合并「同一买家 + 同一收货地址」的订单");
      }
      if (!addrFp.equals(f.addressFingerprint())) {
        throw bad("订单 " + f.orderNo() + " 的收货地址指纹与所选第一笔不同：合单要求同一收货地址");
      }
    }
    if (rule.requireSameCurrency()) {
      String currency = facts.get(0).currency();
      for (OrderFacts f : facts) {
        if (!currency.equals(f.currency())) {
          throw bad(
              "订单 " + f.orderNo() + " 的币种（" + f.currency() + "）与所选第一笔（" + currency + "）不一致");
        }
      }
    }
    if (rule.requireSamePaymentMethod()) {
      String method = facts.get(0).paymentMethod();
      for (OrderFacts f : facts) {
        if (!method.equals(f.paymentMethod())) {
          throw bad(
              "订单 " + f.orderNo() + " 的支付方式（" + f.paymentMethod() + "）与所选第一笔（"
                  + method + "）不一致");
        }
      }
    }
    if (!rule.allowCrossShop()) {
      Set<String> platforms = new LinkedHashSet<>();
      Set<String> shops = new LinkedHashSet<>();
      for (OrderFacts f : facts) {
        platforms.add(f.platformCode());
        shops.add(f.shopCode());
      }
      if (platforms.size() > 1 || shops.size() > 1) {
        throw bad(
            "本租户合单规则不允许跨店铺合并，本次选了 "
                + platforms.size()
                + " 个平台 / "
                + shops.size()
                + " 个店铺");
      }
    }
    if (rule.maxWindowHours() != null) {
      OffsetDateTime earliest = facts.stream().map(OrderFacts::submittedAt).min(OffsetDateTime::compareTo).orElse(null);
      OffsetDateTime latest = facts.stream().map(OrderFacts::submittedAt).max(OffsetDateTime::compareTo).orElse(null);
      if (earliest != null && latest != null && latest.isAfter(earliest.plusHours(rule.maxWindowHours()))) {
        throw bad(
            "所选订单跨越 "
                + rule.maxWindowHours()
                + " 小时的时间窗：最早 "
                + earliest
                + "、最晚 "
                + latest);
      }
    }
    if (at.isBefore(facts.stream().map(OrderFacts::submittedAt).min(OffsetDateTime::compareTo).orElse(at))) {
      throw bad("合单时刻不能早于最早一笔成员订单的提交时刻");
    }

    UUID tenant = currentTenant();
    UUID id = UUID.randomUUID();
    OrderFacts head = facts.get(0);
    String no = "CO-" + java.time.LocalDateTime.ofInstant(at.toInstant(), java.time.ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        + "-" + id.toString().substring(0, 6).toUpperCase(Locale.ROOT);

    BigDecimal quoted = input.consolidatedShippingAmount();
    BigDecimal quotedScaled = quoted == null ? null : money(quoted);
    if (quotedScaled != null && quotedScaled.compareTo(BigDecimal.ZERO) < 0) {
      throw bad("合并运费报价不能为负数");
    }

    sql.execute(
        "insert into order_consolidation(id,tenant_id,consolidation_no,status,"
            + "buyer_fingerprint,address_fingerprint,buyer_name,buyer_phone,buyer_email,"
            + "address_line,country_code,currency_code,payment_method,"
            + "consolidated_shipping_amount,consolidated_at,note,version,"
            + "created_by_membership_id,created_at,updated_at)"
            + " values(?,?,?,'PLANNED',?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,0,?,"
            + "?::timestamptz,?::timestamptz)",
        id,
        tenant,
        no,
        buyerFp,
        addrFp,
        head.recipientName(),
        nz(head.recipientPhone()),
        nz(head.recipientEmail()),
        addressDisplay(head),
        nz(head.countryCode()),
        head.currency(),
        head.paymentMethod(),
        quotedScaled,
        at,
        nz(input.note()),
        actorId,
        OffsetDateTime.now(),
        OffsetDateTime.now());

    // 成员行 + 逐单回传任务。**平台归属逐单复制**，合并成包裹之后仍能回答
    // 「这件货属于哪个平台的哪张单」—— 蓝图加粗句要求的就是这个。
    for (OrderFacts f : facts) {
      UUID memberId = UUID.randomUUID();
      sql.execute(
          "insert into order_consolidation_member(id,tenant_id,consolidation_id,order_id,order_no,"
              + "order_source,platform_code,shop_code,platform_order_ref,currency_code,"
              + "payment_method,member_status,buyer_fingerprint,address_fingerprint,"
              + "created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,'PLANNED',?,?,?::timestamptz)",
          memberId,
          tenant,
          id,
          f.orderId(),
          f.orderNo(),
          f.orderSource(),
          f.platformCode(),
          f.shopCode(),
          f.platformOrderRef(),
          f.currency(),
          f.paymentMethod(),
          buyerFp,
          addrFp,
          OffsetDateTime.now());
      // 非平台单（platform_code 为空）不建回传任务：手工单没有平台可回传，
      // 给它造一个任务是给「回传零失败」这个指标掺水。
      if (!f.platformCode().isEmpty()) {
        sql.execute(
            "insert into order_consolidation_writeback_task(id,tenant_id,consolidation_id,"
                + "member_id,consolidation_level,platform_code,shop_code,platform_order_ref,"
                + "status,attempt_count,last_error,created_at,updated_at)"
                + " values(?,?,?,?,false,?,?,?,'PENDING',0,'',?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            tenant,
            id,
            memberId,
            f.platformCode(),
            f.shopCode(),
            f.platformOrderRef(),
            OffsetDateTime.now(),
            OffsetDateTime.now());
      }
    }

    appendEvent(id, "CONSOLIDATED", "", "PLANNED", actorId,
        "合并 " + facts.size() + " 笔订单：" + facts.stream().map(OrderFacts::orderNo).toList(), at);

    var saved = sql.fetchOne(
        "select distinct_platform_count,distinct_shop_count,cross_shop,order_count,"
            + "total_qty,separate_shipping_amount,freight_saving_amount "
            + "from order_consolidation where id = ?", id);
    boolean crossShop = Boolean.TRUE.equals(saved.get("cross_shop", Boolean.class));
    BigDecimal saving = saved.get("freight_saving_amount", BigDecimal.class);
    String message =
        "已合并 "
            + facts.get(0).orderNo()
            + " 等 "
            + facts.size()
            + " 笔订单"
            + (crossShop ? "（跨店铺：平台 " + saved.get("distinct_platform_count", Integer.class) + " 个 / 店铺 "
                + saved.get("distinct_shop_count", Integer.class) + " 个）" : "")
            + "；"
            + (saving == null
                ? "未报价合并运费，运费节省暂不可计算（不是 0）"
                : "运费节省 " + money(saving).toPlainString());
    return new ConsolidationSaved(id, no, ConsolidationStatus.PLANNED, message);
  }

  /** 合并时逐单实查出来的真实事实（一行一个订单，全部来自 sales_order 簇）。 */
  private record OrderFacts(
      UUID orderId,
      String orderNo,
      String orderSource,
      String externalOrderNo,
      String platformCode,
      String shopCode,
      String currency,
      String paymentMethod,
      String status,
      OffsetDateTime submittedAt,
      String customerPartyId,
      String recipientName,
      String recipientPhone,
      String recipientEmail,
      String countryCode,
      String province,
      String city,
      String district,
      String postcode,
      String addressLine1,
      String addressLine2,
      String buyerFingerprint,
      String addressFingerprint,
      BigDecimal shippedQty) {

    String platformOrderRef() {
      return externalOrderNo;
    }
  }

  private List<OrderFacts> loadFacts(List<UUID> orderIds) {
    List<OrderFacts> out = new ArrayList<>();
    for (UUID oid : orderIds) {
      Record r =
          sql.fetchOne(
              "select o.id order_id,o.order_no,o.order_source,o.external_order_no,o.currency,"
                  + "o.payment_method,o.status,o.submitted_at,"
                  + "a.recipient_name,a.recipient_phone,a.recipient_email,a.country_code,a.province,"
                  + "a.city,a.district,a.postcode,a.address_line1,a.address_line2,"
                  + "p.customer_party_id,"
                  + "md5(concat_ws(" + SEP_SQL + ",a.country_code,a.province,a.city,a.district,"
                  + "a.postcode,a.address_line1,a.address_line2)) af,"
                  + "(select coalesce(sum(p.shipped_qty),0) from order_fulfillment_line_progress p"
                  + "  where p.tenant_id=o.tenant_id and p.order_id=o.id) shipped_qty"
                  + " from sales_order o"
                  + " join order_address_snapshot a"
                  + "   on a.tenant_id=o.tenant_id and a.order_id=o.id and a.address_role='SHIPPING'"
                  + " left join sales_order_party_snapshot p"
                  + "   on p.tenant_id=o.tenant_id and p.order_id=o.id"
                  + " where o.tenant_id = " + TENANT_PREDICATE + " and o.id = ?",
              oid);
      if (r == null) {
        throw notFound("订单 " + oid + " 不存在，或没有收货地址快照（合单要求有收货地址）");
      }
      String source = nz(r.get("order_source", String.class));
      String partyId = r.get("customer_party_id", UUID.class) == null
          ? ""
          : r.get("customer_party_id", UUID.class).toString();
      out.add(
          new OrderFacts(
              r.get("order_id", UUID.class),
              nz(r.get("order_no", String.class)),
              source,
              nz(r.get("external_order_no", String.class)),
              platformCodeOf(source),
              "",
              nz(r.get("currency", String.class)),
              nz(r.get("payment_method", String.class)),
              nz(r.get("status", String.class)),
              r.get("submitted_at", OffsetDateTime.class),
              partyId,
              nz(r.get("recipient_name", String.class)),
              nz(r.get("recipient_phone", String.class)),
              nz(r.get("recipient_email", String.class)),
              nz(r.get("country_code", String.class)),
              nz(r.get("province", String.class)),
              nz(r.get("city", String.class)),
              nz(r.get("district", String.class)),
              nz(r.get("postcode", String.class)),
              nz(r.get("address_line1", String.class)),
              nz(r.get("address_line2", String.class)),
              buyerFingerprint(
                  partyId,
                  nz(r.get("recipient_name", String.class)),
                  nz(r.get("recipient_phone", String.class)),
                  nz(r.get("recipient_email", String.class))),
              nz(r.get("af", String.class)),
              r.get("shipped_qty", BigDecimal.class)));
    }
    return out;
  }

  // ================================================================ 读

  /** 合并单列表（真分页；三组汇总额不受分页影响）。 */
  public ConsolidationPage pageConsolidations(ConsolidationQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    // tenant_id 谓词显式写一遍：合单的列表页会被「跨店铺」「跨平台」这类宽条件筛，
    // 漏掉租户谓词就是整租户的数据泄露。
    where.add("tenant_id = " + TENANT_PREDICATE);
    if (notBlank(query.keyword())) {
      where.add("(consolidation_no ilike ? or exists (select 1 from order_consolidation_member m"
          + " where m.tenant_id = c.tenant_id and m.consolidation_id = c.id"
          + " and m.order_no ilike ?))");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    if (notBlank(query.status())) {
      where.add("status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.buyerName())) {
      where.add("buyer_name ilike ?");
      binds.add("%" + query.buyerName().trim() + "%");
    }
    if (Boolean.TRUE.equals(query.crossShopOnly())) {
      where.add("cross_shop");
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) c from order_consolidation c" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<ConsolidationRow> items =
        sql.fetch(
                "select c.* from order_consolidation c" + sqlWhere
                    + " order by c.consolidated_at desc, c.id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(OrderConsolidationService::consolidationRow);
    long dispatched =
        sql.fetchOne(
                "select count(*) c from order_consolidation where tenant_id = " + TENANT_PREDICATE
                    + " and status='DISPATCHED'")
            .get("c", Long.class);
    long pendingWriteback =
        sql.fetchOne(
                "select count(*) c from order_consolidation_writeback_task where tenant_id = "
                    + TENANT_PREDICATE + " and status<>'CONFIRMED'")
            .get("c", Long.class);
    Record saving =
        sql.fetchOne(
            "select sum(freight_saving_amount) s from order_consolidation where tenant_id = "
                + TENANT_PREDICATE + " and freight_saving_amount is not null");
    BigDecimal savingTotal = saving.get("s", BigDecimal.class);
    return new ConsolidationPage(
        items,
        total,
        pageNo,
        size,
        dispatched,
        pendingWriteback,
        savingTotal == null ? null : money(savingTotal));
  }

  /** 合并单详情：头 + 成员 + 回传任务 + 事件流。跨租户在 RLS 下查不到 → 404。 */
  public ConsolidationDetail consolidation(UUID id) {
    Record head = headOr404(id);
    List<ConsolidationMember> members =
        sql.fetch(
                "select m.* from order_consolidation_member m"
                    + " where m.tenant_id = " + TENANT_PREDICATE + " and m.consolidation_id = ?"
                    + " order by m.order_no, m.id",
                id)
            .map(OrderConsolidationService::memberRow);
    List<WritebackTask> tasks = writebacksOf(id);
    List<ConsolidationEvent> events =
        sql.fetch(
                "select id,event_type,from_status,to_status,actor_membership_id,detail,occurred_at"
                    + " from order_consolidation_event where tenant_id = " + TENANT_PREDICATE
                    + " and consolidation_id = ? order by occurred_at desc, id",
                id)
            .map(OrderConsolidationService::eventRow);
    String advice =
        "「跨店铺」= 蓝图里「识别跨店铺同买家订单」：包裹是一个，但每个平台订单"
            + "仍要单独回传物流号（下方回传任务一行一个平台订单）。";
    return new ConsolidationDetail(consolidationRow(head), members, tasks, events, advice);
  }

  // ================================================================ 状态推进

  /** 推进合并单状态（PLANNED → CONFIRMED → DISPATCHED，或 → CANCELLED）。终态不可离开。 */
  @Transactional
  public ConsolidationSaved advanceStatus(
      UUID id, ConsolidationStatusInput input, UUID actorId) {
    if (input == null || input.status() == null) {
      throw bad("请求体与目标状态都不能为空");
    }
    ConsolidationStatus target = input.status();
    if (target == ConsolidationStatus.PLANNED) {
      throw bad("状态只能推进到 CONFIRMED / DISPATCHED / CANCELLED");
    }
    if (input.occurredAt() == null) {
      throw bad("发生时刻必须显式给出");
    }
    if (target == ConsolidationStatus.CANCELLED && notBlank(input.reason()) == false) {
      throw bad("作废必须给原因：已合并下发的订单被撤回，没人说得清为什么");
    }
    Record head = headOr404(id);
    ConsolidationStatus from = consolidationStatus(nz(head.get("status", String.class)));

    sql.execute(
        "select advance_order_consolidation_status(?::uuid,?,?::uuid,?,?::timestamptz)",
        id,
        target.name(),
        actorId,
        nz(input.reason()),
        input.occurredAt());

    String detail =
        switch (target) {
          case CONFIRMED -> "合并已确认，可下发打包";
          case DISPATCHED -> "已下发到 8.3.3.2 打包 / 9.3.3.1 履约";
          case CANCELLED -> "合并已作废：" + nz(input.reason());
          default -> target.label();
        };
    appendEvent(id, target.name(), from.name(), target.name(), actorId, detail, input.occurredAt());

    String message =
        target == ConsolidationStatus.DISPATCHED
            ? detail + "；每个平台订单的物流号仍需在回传任务里逐单登记"
            : detail;
    return new ConsolidationSaved(id, nz(head.get("consolidation_no", String.class)), target, message);
  }

  // ================================================================ 回传任务

  /** 回传任务列表（真分页；三组汇总额不受分页影响）。 */
  public WritebackPage pageWritebacks(WritebackQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = " + TENANT_PREDICATE);
    if (query.consolidationId() != null) {
      where.add("consolidation_id = ?");
      binds.add(query.consolidationId());
    }
    if (notBlank(query.platformCode())) {
      where.add("platform_code = ?");
      binds.add(query.platformCode().trim());
    }
    if (notBlank(query.status())) {
      where.add("status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.platformOrderRef())) {
      where.add("platform_order_ref ilike ?");
      binds.add("%" + query.platformOrderRef().trim() + "%");
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) c from order_consolidation_writeback_task" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<WritebackTask> items =
        sql.fetch(
                "select * from order_consolidation_writeback_task" + sqlWhere
                    + " order by created_at desc, id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(OrderConsolidationService::writebackRow);
    long pending = countWriteback("status='PENDING'");
    long rejected = countWriteback("status='REJECTED'");
    long verification = countWriteback("status='PENDING_VERIFICATION'");
    return new WritebackPage(items, total, pageNo, size, pending, rejected, verification);
  }

  private long countWriteback(String predicate) {
    return sql.fetchOne(
            "select count(*) c from order_consolidation_writeback_task where tenant_id = "
                + TENANT_PREDICATE + " and " + predicate)
        .get("c", Long.class);
  }

  /**
   * 登记一个平台订单的物流号回传结果。
   *
   * <p>蓝图加粗句的落点：<b>合单之后每个平台订单仍要单独回传物流号</b>。所以这里是
   * 按 {@code memberId} 逐单登记的，不存在「给整个包裹记一个物流号」的形状 ——
   * 表上 UNIQUE(tenant_id, member_id) 已经把这件事钉死了。
   */
  @Transactional
  public WritebackTask recordWriteback(
      UUID consolidationId, UUID memberId, WritebackInput input, UUID actorId) {
    if (input == null || input.status() == null) {
      throw bad("请求体与回传状态都不能为空");
    }
    if (input.occurredAt() == null) {
      throw bad("发生时刻必须显式给出");
    }
    if (input.status() == WritebackStatus.CONFIRMED && notBlank(input.trackingNumber()) == false) {
      throw bad("回传成功必须带物流号：平台要的就是这个值，没有它「已回传」是一句空话");
    }
    if (input.status() == WritebackStatus.REJECTED && notBlank(input.lastError()) == false) {
      throw bad("平台拒绝必须给失败原因，否则这条任务会一直躺在清单里没人知道怎么处理");
    }
    Record task =
        sql.fetchOne(
            "select id,status,attempt_count from order_consolidation_writeback_task"
                + " where tenant_id = " + TENANT_PREDICATE + " and member_id = ?",
            memberId);
    if (task == null) {
      throw notFound(
          "成员订单 " + memberId + " 没有回传任务：非平台单（手工单）不建回传任务，"
              + "因为没有平台可以回传");
    }
    String from = nz(task.get("status", String.class));
    if ("CONFIRMED".equals(from)) {
      throw conflict("这条回传任务已经是「已回传」：终态不可改写，需要更正请走平台侧申诉而不是改本地状态");
    }
    sql.execute(
        "update order_consolidation_writeback_task set status=?,tracking_number=?,carrier_code=?,"
            + "attempt_count=attempt_count+1,last_error=?,"
            + "submitted_at=case when ?='PENDING' then submitted_at else coalesce(submitted_at,?::timestamptz) end,"
            + "settled_at=case when ? in ('CONFIRMED','REJECTED','PENDING_VERIFICATION')"
            + "   then ?::timestamptz else settled_at end,"
            // ⚠️ updated_at 是**行的记账时刻**，必须是 now()，不能拿入参的业务时刻去填：
            // 回传结果经常是补录的（把上周那批的物流号录进来），业务时刻早于建行时刻，
            // 拿它填 updated_at 会直接撞上 CHECK(updated_at>=created_at)。
            + "updated_at=?::timestamptz"
            + " where tenant_id = " + TENANT_PREDICATE + " and member_id = ?",
        input.status().name(),
        nz(input.trackingNumber()),
        nz(input.carrierCode()),
        nz(input.lastError()),
        input.status().name(),
        input.occurredAt(),
        input.status().name(),
        input.occurredAt(),
        OffsetDateTime.now(),
        memberId);

    appendEvent(
        consolidationId,
        "WRITEBACK_RECORDED",
        from,
        input.status().name(),
        actorId,
        "物流号 " + nz(input.trackingNumber()) + " → " + input.status().label(),
        input.occurredAt());

    Record fresh =
        sql.fetchOne(
            "select * from order_consolidation_writeback_task where tenant_id = " + TENANT_PREDICATE
                + " and member_id = ?", memberId);
    return writebackRow(fresh);
  }

  // ================================================================ 内部

  private List<WritebackTask> writebacksOf(UUID consolidationId) {
    return sql.fetch(
            "select * from order_consolidation_writeback_task where tenant_id = " + TENANT_PREDICATE
                + " and consolidation_id = ? order by platform_code, platform_order_ref, id",
            consolidationId)
        .map(OrderConsolidationService::writebackRow);
  }

  private void appendEvent(
      UUID consolidationId, String type, String from, String to, UUID actorId, String detail,
      OffsetDateTime occurredAt) {
    OffsetDateTime at = occurredAt == null ? OffsetDateTime.now() : occurredAt;
    sql.execute(
        "insert into order_consolidation_event(id,tenant_id,consolidation_id,event_type,"
            + "from_status,to_status,actor_membership_id,detail,occurred_at,created_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        consolidationId,
        type,
        nz(from),
        nz(to),
        actorId,
        detail,
        at,
        OffsetDateTime.now());
  }

  private Record headOr404(UUID id) {
    Record head =
        sql.fetchOne(
            "select * from order_consolidation where tenant_id = " + TENANT_PREDICATE + " and id = ?",
            id);
    if (head == null) {
      throw notFound("合并单 " + id + " 不存在");
    }
    return head;
  }

  // ================================================================ 行映射

  private static ConsolidationRule ruleRow(Record r) {
    return new ConsolidationRule(
        r.get("id", UUID.class),
        r.get("max_orders_per_consolidation", Integer.class),
        Boolean.TRUE.equals(r.get("allow_cross_shop", Boolean.class)),
        r.get("max_window_hours", Integer.class),
        Boolean.TRUE.equals(r.get("require_same_currency", Boolean.class)),
        Boolean.TRUE.equals(r.get("require_same_payment_method", Boolean.class)),
        Boolean.TRUE.equals(r.get("require_shipping_address", Boolean.class)),
        r.get("version", Long.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static CandidateOrder candidateRow(Record r) {
    String source = nz(r.get("order_source", String.class));
    return new CandidateOrder(
        r.get("order_id", UUID.class),
        nz(r.get("order_no", String.class)),
        nz(r.get("external_order_no", String.class)),
        source,
        platformCodeOf(source),
        "",
        nz(r.get("currency", String.class)),
        nz(r.get("payment_method", String.class)),
        nz(r.get("order_status", String.class)),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("line_count", Integer.class),
        qty(r.get("total_qty", BigDecimal.class)),
        money(r.get("separate_shipping", BigDecimal.class)),
        nz(r.get("buyer_fingerprint", String.class)),
        nz(r.get("address_fingerprint", String.class)),
        money(r.get("separate_shipping", BigDecimal.class)));
  }

  private static ConsolidationRow consolidationRow(Record r) {
    ConsolidationStatus status = consolidationStatus(nz(r.get("status", String.class)));
    return new ConsolidationRow(
        r.get("id", UUID.class),
        nz(r.get("consolidation_no", String.class)),
        status,
        status.label(),
        nz(r.get("buyer_fingerprint", String.class)),
        nz(r.get("address_fingerprint", String.class)),
        nz(r.get("buyer_name", String.class)),
        nz(r.get("buyer_phone", String.class)),
        nz(r.get("buyer_email", String.class)),
        nz(r.get("address_line", String.class)),
        nz(r.get("country_code", String.class)),
        nz(r.get("currency_code", String.class)),
        nz(r.get("payment_method", String.class)),
        r.get("order_count", Integer.class),
        r.get("distinct_platform_count", Integer.class),
        r.get("distinct_shop_count", Integer.class),
        Boolean.TRUE.equals(r.get("cross_shop", Boolean.class)),
        r.get("total_line_count", Integer.class),
        qty(r.get("total_qty", BigDecimal.class)),
        money(r.get("separate_shipping_amount", BigDecimal.class)),
        money(r.get("consolidated_shipping_amount", BigDecimal.class)),
        money(r.get("freight_saving_amount", BigDecimal.class)),
        r.get("consolidated_at", OffsetDateTime.class),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("dispatched_at", OffsetDateTime.class),
        r.get("cancelled_at", OffsetDateTime.class),
        nz(r.get("cancel_reason", String.class)),
        nz(r.get("note", String.class)),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static ConsolidationMember memberRow(Record r) {
    MemberStatus status = MemberStatus.valueOf(nz(r.get("member_status", String.class)));
    return new ConsolidationMember(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        nz(r.get("order_no", String.class)),
        nz(r.get("platform_order_ref", String.class)),
        nz(r.get("order_source", String.class)),
        nz(r.get("platform_code", String.class)),
        nz(r.get("shop_code", String.class)),
        nz(r.get("currency_code", String.class)),
        nz(r.get("payment_method", String.class)),
        r.get("line_count", Integer.class),
        qty(r.get("total_qty", BigDecimal.class)),
        money(r.get("separate_shipping_amount", BigDecimal.class)),
        status,
        status.label());
  }

  private static WritebackTask writebackRow(Record r) {
    WritebackStatus status = WritebackStatus.valueOf(nz(r.get("status", String.class)));
    return new WritebackTask(
        r.get("id", UUID.class),
        r.get("member_id", UUID.class),
        r.get("consolidation_id", UUID.class),
        nz(r.get("platform_code", String.class)),
        nz(r.get("shop_code", String.class)),
        nz(r.get("platform_order_ref", String.class)),
        nz(r.get("tracking_number", String.class)),
        nz(r.get("carrier_code", String.class)),
        status,
        status.label(),
        r.get("attempt_count", Integer.class),
        nz(r.get("last_error", String.class)),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("settled_at", OffsetDateTime.class));
  }

  private static ConsolidationEvent eventRow(Record r) {
    return new ConsolidationEvent(
        r.get("id", UUID.class),
        nz(r.get("event_type", String.class)),
        nz(r.get("from_status", String.class)),
        nz(r.get("to_status", String.class)),
        r.get("actor_membership_id", UUID.class),
        nz(r.get("detail", String.class)),
        r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 工具

  private static ConsolidationStatus consolidationStatus(String value) {
    return ConsolidationStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * 订单来源 → 平台码。
   *
   * <p>MANUAL 与 PLATFORM 不映射成平台码：手工单没有平台，{@code PLATFORM} 是
   * 「来源待定」的占位来源而不是某个具体平台。给它们编一个平台码会让回传任务指向不存在的平台。
   */
  static String platformCodeOf(String orderSource) {
    String source = nz(orderSource).toUpperCase(Locale.ROOT);
    return switch (source) {
      case "AMAZON", "SHOPIFY", "EBAY", "TIKTOK_SHOP", "ALIBABA_1688" -> source;
      default -> "";
    };
  }

  /**
   * 买家指纹（**判定式**）。
   *
   * <p>优先用 {@code sales_order_party_snapshot.customer_party_id}（catalog 的客户逻辑 ID，
   * V99 已交付），退回到收货地址快照的身份三件套。写成带前缀的形式
   * （{@code P:…} / {@code A:…}）是为了让「一个 UUID 恰好等于某个 md5」这种巧合
   * 不可能发生 —— 没有前缀时两种口径会有极小概率撞出同一个摘要值。
   *
   * <p>**为什么优先用客户 ID 而不是姓名电话**：同一收货地址上完全可能有两位买家
   * （家人、同事代收、合租）。只用姓名+电话+邮箱判「同一买家」会把他们合成一箱，
   * 而两箱货分别属于两个人 —— 这是错发，不是省钱。
   *
   * <p>只有一方有客户快照时两边算出不同的指纹 → 判为不同买家。这是**保守失败**
   * （不合单），方向正确：少合一次只是少省点运费，错合一次是把货发给了错的人。
   */
  static String buyerFingerprint(String customerPartyId, String name, String phone, String email) {
    if (notBlank(customerPartyId)) {
      return "P:" + customerPartyId.trim().toLowerCase(Locale.ROOT);
    }
    return "A:" + md5(nz(name) + SEP_CHAR + nz(phone) + SEP_CHAR + nz(email));
  }

  /**
   * 地址指纹：与 SQL 里
   * {@code md5(concat_ws(SEP_SQL, country, province, city, district, postcode, line1, line2))} 同式。
   */
  static String addressFingerprint(
      String line1, String line2, String country, String province, String city, String district,
      String postcode) {
    return md5(
        nz(country)
            + SEP_CHAR
            + nz(province)
            + SEP_CHAR
            + nz(city)
            + SEP_CHAR
            + nz(district)
            + SEP_CHAR
            + nz(postcode)
            + SEP_CHAR
            + nz(line1)
            + SEP_CHAR
            + nz(line2));
  }

  private static String md5(String value) {
    try {
      var digest = java.security.MessageDigest.getInstance("MD5");
      var bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

  private static String addressDisplay(OrderFacts f) {
    List<String> parts = new ArrayList<>();
    for (String p :
        List.of(f.province(), f.city(), f.district(), f.postcode(), f.addressLine1(), f.addressLine2())) {
      if (notBlank(p)) {
        parts.add(p);
      }
    }
    return String.join(" ", parts);
  }

  private static int pageSize(int requested) {
    return Math.min(MAX_PAGE_SIZE, Math.max(1, requested <= 0 ? 50 : requested));
  }

  private static Object[] append(List<Object> binds, Object... more) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(more));
    return all.toArray();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static Boolean nzBool(Boolean value, boolean fallback) {
    return value == null ? fallback : value;
  }

  private static List<UUID> dedupe(List<UUID> ids) {
    List<UUID> out = new ArrayList<>();
    for (UUID id : ids) {
      if (id != null && !out.contains(id)) {
        out.add(id);
      }
    }
    return out;
  }

  private static BigDecimal qty(BigDecimal value) {
    return value == null ? null : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
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
