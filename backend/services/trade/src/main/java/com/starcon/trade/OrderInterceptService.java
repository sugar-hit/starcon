package com.starcon.trade;

import static com.starcon.trade.OrderInterceptModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.2.1.3 异常拦截：八项命中判定 + 建议处置 + 人工处置。
 *
 * <p><b>七条实现纪律</b>：
 *
 * <ol>
 *   <li><b>本类里没有任何一处写 {@code final_disposition}</b>：改最终处置的唯一入口是
 *       {@link #disposition}，而它要求一条已认证的人工成员身份。蓝图加粗句「拦截不自动取消，
 *       默认转人工」在服务层就成立 —— 不是靠注释约定，是靠「这里根本没有那个 setter」。
 *   <li><b>计数与建议处置由 DB 触发器实算</b>：服务层只负责算出八项各自的结论，
 *       {@code order_intercept_apply_totals} 负责计数、按严重度判建议处置。
 *       两处各算一遍的结果迟早会不一致，而「检查上写着命中 1 条，它与八行明细算出来的不一样」
 *       是无法排查的。
 *   <li><b>四项是真 SQL</b>：地址信息不全、重复下单、低于成本、风控疑似全部直接查本服务
 *       sales_order 簇与 V149 / V150 的表；限制清单与成本基线是租户可维护的真实表；
 *       地址可投递性是**上游未交付**，如实落成 UPSTREAM_UNAVAILABLE 而不是猜一个结论。
 *   <li><b>复用 9.2.2.2 的结论而不是绕过它</b>：{@link #suspectedFraud} 读
 *       {@code order_risk_assessment}，并保留它「分数不能单独作为拒绝依据」的口径 ——
 *       读它的人工结论 {@code final_action}，不拿 {@code risk_score} 自己当拒绝依据。
 *   <li><b>判定基准时刻钉在订单 submitted_at 上</b>，不是 {@code now()}：
 *       同一张单重复检查一万次，八项结论逐位相同。成本基线与限制清单的生效期也相对这个时刻判，
 *       且**排除基准时刻之后采集的记录** —— 订单提交之后才换的成本价，对这一单没有贡献。
 *   <li><b>每条查询都显式带租户谓词</b>：判定查询是「同一 buyer_key」「同一收货国」的跨订单宽条件，
 *       漏掉租户谓词就是跨租户归因 —— A 家的黑名单会拦下 B 家的订单，而 B 家连自己被拦都不知道。
 *   <li><b>时间一律 {@code ?::timestamptz} 绑定 OffsetDateTime</b>，绝不用
 *       {@code Timestamp.valueOf(LocalDateTime...)}（那会静默偏 −28800s）。
 * </ol>
 */
@Service
public class OrderInterceptService {

  /** 单页最大行数。拦截清单是凭据不是流水，但仍设上限防一次拉爆 JVM。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final int QTY_SCALE = 6;
  private static final int MONEY_SCALE = 4;
  private static final int SCORE_SCALE = 4;
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /**
   * 买家身份键的分隔符：不可见 unit separator（U+001F），与 V149 / V144 同一口径。
   *
   * <p>刻意<b>不</b>用 {@code '|'}：收件人字段由运营自由录入，{@code '|'} 会让「Smith|John」
   * 与「Smith」+「John」两组字段拼出同一个串 —— 那是一次<b>跨买家的拦截归因</b>，
   * 比不归因严重得多。
   */
  private static final char SEP_CHAR = '\u001F';

  private static final String TENANT =
      " nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 只有信箱类地址且无公司名才算「只有信箱」。 */
  private static final Pattern PO_BOX =
      Pattern.compile("(?i)\\b(p\\.?\\s?o\\.?\\s?box|post\\s?office\\s?box)\\b");

  /** 邮编是同一个数字重复（如 00000）—— 那是保留码，不是真实邮编。 */
  private static final Pattern REPEATED_DIGITS = Pattern.compile("^(\\d)\\1+$");

  private final DSLContext sql;

  public OrderInterceptService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：蓝图输入里的「地址校验服务；16 合规与风控信号；5 定价（低于成本线）」
   * 三样，在集成头分别是「未开发」「在别的服务的独立库且 24 个 L4 全未交付」「没有任何数据源」。
   * **哪几项真接通、哪几项是租户自己维护的、哪几项现在根本判不了，必须由接口说**，
   * 而不是让调用方从 404 或空数组里猜哪一环没通。
   */
  public InterceptCapabilities capabilities() {
    boolean configured = policyOrNull() != null;
    return new InterceptCapabilities(
        List.of(
            new InterceptCapabilityCheck(
                "ADDRESS_INCOMPLETE",
                InterceptCheckCode.ADDRESS_INCOMPLETE.label(),
                "收货地址快照 order_address_snapshot（V99，本服务同库）实算",
                true,
                "无需阈值：缺邮编 / 缺电话 / 只有信箱 / 地址过短 / 省市皆空 / 保留邮编，由列直接推出",
                null),
            new InterceptCapabilityCheck(
                "ADDRESS_DELIVERABILITY",
                InterceptCheckCode.ADDRESS_DELIVERABILITY.label(),
                "9.2.2.1 地址与支付校验（**未开发**）",
                false,
                "无阈值（上游未交付）",
                "9.2.2.1 地址与支付校验在集成头**未开发**，本服务没有任何可读的地址校验结果，"
                    + "禁运国家清单、可投递性校验、支付一致性都在它名下。"
                    + "⇒ 本项恒为 UPSTREAM_UNAVAILABLE，**不是**「地址没问题」。"
                    + "蓝图同一条里的「地址**信息不全**」是另一件事，由收货地址快照的列真算（见上一行）。"
                    + "9.2.2.1 交付后由同步器按同一形状写入并把状态改掉，表结构与判定逻辑都不用改。"),
            new InterceptCapabilityCheck(
                "BLACKLISTED_BUYER",
                InterceptCheckCode.BLACKLISTED_BUYER.label(),
                "9.2.1.2 买家档案 order_review_buyer_profile（V149，本服务同库，只读）",
                true,
                "无需阈值：buyer_profile.blacklisted 是布尔判定",
                "卷 16 合规的 24 个 L4 **全部未交付**，黑名单在合规服务的独立库里，"
                    + "按模块边界纪律禁止跨服务 SQL ⇒ 本模块读不到它。"
                    + "当前依据是 V149 的租户自维护档案（运营给买家拉黑，带拉黑理由与时刻）。"
                    + "⚠️ **没有档案 ≠ 不是黑名单**：无档案落 NO_REFERENCE，"
                    + "把「没登记过」当成「核验过、干净」会让合规黑名单形同虚设。"),
            new InterceptCapabilityCheck(
                "DUPLICATE_ORDER",
                InterceptCheckCode.DUPLICATE_ORDER.label(),
                "本服务 sales_order + order_address_snapshot（V99）真 SQL：同 buyer_key 窗口内、本单之前的已提交笔数",
                true,
                "order_intercept_policy.duplicate_window_hours / duplicate_max_orders",
                null),
            new InterceptCapabilityCheck(
                "BELOW_COST_DISCOUNT",
                InterceptCheckCode.BELOW_COST_DISCOUNT.label(),
                "本服务 sales_order_line（V99）净额单价 vs 租户成本基线 order_intercept_sku_cost（真 SQL 比值）",
                true,
                "order_intercept_policy.below_cost_tolerance_rate",
                "卷 05 定价未交付，本服务**没有任何成本来源**，所以成本基线是租户可维护的真实表"
                    + "（照抄 V92 fulfillment_path_cost_profile 同一手法）。"
                    + "⚠️ 币种是硬口径：成本基线币种与订单币种不一致的行**不参与比较**（落 NO_REFERENCE），"
                    + "本服务没有汇率来源（10 财务未交付），跨币种比较是纯噪声。"
                    + "⚠️ 折扣金额是负数（V99 强制 <= 0），净额是 line_amount **加** discount_amount；"
                    + "写成减号会让每一行都算成高价，于是「异常折扣」永远不会命中 —— "
                    + "一个永不命中的检查比没有检查更糟，因为界面会显示「无异常折扣」。"),
            new InterceptCapabilityCheck(
                "SUSPECTED_FRAUD",
                InterceptCheckCode.SUSPECTED_FRAUD.label(),
                "9.2.2.2 风控评估 order_risk_assessment（V150，本服务同库，只读）",
                true,
                "order_intercept_policy.fraud_score_threshold",
                "**复用** 9.2.2.2 的结论，不重算风控分数，也不绕过它「风控分数不能单独作为拒绝依据」的口径："
                    + "本模块读它的 risk_score 与 recommended_action 做**建议**，"
                    + "而当它已有人工结论 final_action 时按该结论定档。"
                    + "V150 里一条风险分数在本服务产不出终局动作，本模块同样产不出 —— "
                    + "本模块的最终处置仍然只由人工处置留痕产生。"),
            new InterceptCapabilityCheck(
                "COUNTRY_RESTRICTED",
                InterceptCheckCode.COUNTRY_RESTRICTED.label(),
                "租户可维护的限制清单 order_intercept_restriction（SHIPPING_COUNTRY）",
                false,
                "order_intercept_restriction（按收货国码查，BLOCK/REVIEW 决定建议处置档位）",
                "9.2.2.1 的禁运国家清单**未交付**，16 合规的国别风险清单也未交付，"
                    + "且分属别的服务的独立库，按模块边界纪律禁止跨服务读表。"
                    + "当前依据是租户自维护的限制清单（真实业务数据、真实参与判定，"
                    + "restriction_source 如实标注来源）。"
                    + "正式清单到位后由同步器按同一形状写入并改来源枚举，表结构与判定逻辑都不用改。"),
            new InterceptCapabilityCheck(
                "PLATFORM_COMPLIANCE",
                InterceptCheckCode.PLATFORM_COMPLIANCE.label(),
                "租户可维护的限制清单 order_intercept_restriction（PLATFORM_SOURCE）",
                false,
                "order_intercept_restriction（按订单来源查，BLOCK/REVIEW 决定建议处置档位）",
                "16 合规的平台合规清单与 2 平台的来源合规策略都未交付。"
                    + "⚠️ **平台维度不是店铺维度**：实查 V99，sales_order **没有 shop_code 列**，"
                    + "所以本项只能按 order_source（平台级）判，店铺级合规风险在本服务无处可判"
                    // 末项的收尾写在同一行上：第一个 ) 收 InterceptCapabilityCheck，第二个收 List.of。
                    // ⚠️ 这里**不能**留尾逗号 —— Java 的方法调用实参列表不允许尾逗号（只有数组初始化器允许），
                    //   写成 `...拦住。"),` 再另起一行 `)` 会在那个 `)` 上报「非法的表达式开始」，
                    //   而错误信息与真正的成因（多了一个逗号）毫无关系。
                    + "（shopScopeSupported = false）。编一个店铺维度只会让界面显示「已按店铺限制」而实际一单都没拦住。")),
        "拦截不自动取消，默认转人工——误拦真实订单的代价通常高于人工看一眼。"
            + "本模块的落点：① 建议处置与最终处置是**两个字段**，finalDisposition 的词表"
            + "（RELEASE / HOLD / CANCEL）**不含任何「自动」取值**；"
            + "② 表级 CHECK manualReviewRequired = (recommendedDisposition <> 'RELEASE')，"
            + "连「建议取消」都带「需人工处置」；③ guard_order_intercept_final_disposition 触发器"
            + "要求最终处置必须已有 order_intercept_disposition 人工留痕；"
            + "④ guard_order_intercept_disposition_requires_manual 触发器要求人工处置只挂在需人工处置的检查上"
            + "（挡住「给一张放行的单补一条已取消」这个反方向越权）；"
            + "⑤ 服务层**没有任何一处**写 finalDisposition，改它的唯一入口是 POST /checks/{id}/disposition 且要人工身份。",
        "① 八项各自判定并落一行明细（缺失与未接通也占行）；"
            + "② 存在 BLOCK 级命中（禁运国 BLOCK / 平台来源 BLOCK / 风控已人工拒发）→ 建议 CANCEL；"
            + "③ 否则存在任一命中 → 建议 MANUAL_REVIEW；"
            + "④ 否则（八项全部核对过且无一命中，或 ASSESS_KNOWN_ONLY 下仅剩缺参照项）→ 建议 RELEASE；"
            + "⑤ **一条都没评上，或 BLOCK_DECISION 下有缺参照时，一律不出「放行」**，只给「转人工」——"
            + "放行一个没人看过的单与误拦真实订单一样是真实损失，而加粗句只说了后者更贵，不是只说了后者。",
        false,
        false,
        false,
        configured,
        "上游缺口：9.2.2.1 地址与支付校验（未开发 ⇒ 地址可投递性判不了）、"
            + "卷 16 合规（24 个 L4 全部未交付 ⇒ 黑名单与平台合规清单读不到）、"
            + "卷 05 定价（未交付 ⇒ 成本基线靠租户维护）、9.2.1.1 待审队列（未开发）。"
            + "下游缺口：2 平台取消回写、11 售后（欺诈售后）、16 合规（黑名单）均未交付 —— "
            + "本模块**只产出建议与人工结论**，不代劳下游动作。"
            + "本模块对上述缺口都不假装接通：capabilities 逐项标 upstreamDelivered=false，"
            + "跨服务读表一律禁止（crossServiceReadAvailable = false）。");
  }

  // ================================================================ 策略

  /** 拦截策略读取。没登记过就是 404 —— 不是一份带默认值的策略。 */
  public InterceptPolicy policy() {
    var policy = policyOrNull();
    if (policy == null) {
      throw notFound(unconfiguredPolicy());
    }
    return policy;
  }

  private static String unconfiguredPolicy() {
    return "本租户尚未登记拦截策略。拦截结论会决定一单发不发，"
        + "「没配策略 = 默认全放行」与蓝图加粗句是反的，所以本模块不提供隐含默认值："
        + "请先 PUT /trade/order-intercept/policy 登记策略。";
  }

  private InterceptPolicy policyOrNull() {
    Record r = sql.fetchOne("select * from order_intercept_policy");
    return r == null ? null : policyRow(r);
  }

  private InterceptPolicy requirePolicy() {
    var policy = policyOrNull();
    if (policy == null) {
      throw conflict(unconfiguredPolicy());
    }
    if (!policy.enabled()) {
      throw conflict(
          "本租户的拦截策略「"
              + policy.policyName()
              + "」处于停用状态：停用策略继续检查等于让运营以为拦截还在跑。"
              + "请先 PUT /policy 把 enabled 打开。");
    }
    return policy;
  }

  /** 登记 / 更新拦截策略（upsert，一租户一条）。 */
  @Transactional
  public InterceptPolicy savePolicy(InterceptPolicyInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    if (input.fraudScoreThreshold().compareTo(HUNDRED) > 0) {
      throw bad(
          "疑似欺诈阈值必须落在 0..100（当前 "
              + input.fraudScoreThreshold()
              + "）。它与 9.2.2.2 的 risk_score 同一刻度，不换算；"
              + "超过 100 会让这一项永远判不命中，而界面显示「无异常」");
    }
    UUID tenant = currentTenant();
    var existing = sql.fetchOne("select id from order_intercept_policy where tenant_id = ?", tenant);
    if (existing == null) {
      sql.execute(
          "insert into order_intercept_policy(id,tenant_id,policy_name,enabled,"
              + "duplicate_window_hours,duplicate_max_orders,below_cost_tolerance_rate,"
              + "fraud_score_threshold,missing_reference_policy,version,created_at,updated_at)"
              + " values(?,?,?,true,?,?,?,?,?,0,now(),now())",
          UUID.randomUUID(),
          tenant,
          input.policyName().trim(),
          input.duplicateWindowHours(),
          input.duplicateMaxOrders(),
          rate(input.belowCostToleranceRate()),
          score(input.fraudScoreThreshold()),
          input.missingReferencePolicy().name());
      return policy();
    }
    // ⚠️ version 必须递增：V153 的 guard_order_intercept_policy_versioned 强制这一点，
    // 因为检查记录引用的是 (policy_id, policy_version)，同名策略会让「当时按哪一版判的」无法复算。
    sql.execute(
        "update order_intercept_policy set policy_name=?,enabled=true,"
            + "duplicate_window_hours=?,duplicate_max_orders=?,below_cost_tolerance_rate=?,"
            + "fraud_score_threshold=?,missing_reference_policy=?,version=version+1"
            + " where tenant_id = ?",
        input.policyName().trim(),
        input.duplicateWindowHours(),
        input.duplicateMaxOrders(),
        rate(input.belowCostToleranceRate()),
        score(input.fraudScoreThreshold()),
        input.missingReferencePolicy().name(),
        tenant);
    return policy();
  }

  // ================================================================ 限制清单

  /** 限制清单列表（真分页 + 真搜索）。 */
  public InterceptRestrictionPage pageRestrictions(InterceptRestrictionQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = " + TENANT);
    if (notBlank(query.keyword())) {
      where.add("(restriction_target ilike ? or reason ilike ? or source_reference ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
      binds.add(k);
    }
    if (notBlank(query.restrictionKind())) {
      where.add("restriction_kind = ?");
      binds.add(query.restrictionKind().trim().toUpperCase(Locale.ROOT));
    }
    if (query.restrictionEffect() != null) {
      where.add("restriction_effect = ?");
      binds.add(query.restrictionEffect().name());
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) c from order_intercept_restriction r" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<InterceptRestriction> items =
        sql.fetch(
                "select r.* from order_intercept_restriction r"
                    + sqlWhere
                    + " order by r.restriction_kind, r.restriction_target, r.id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(OrderInterceptService::restrictionRow);
    return new InterceptRestrictionPage(items, total, pageNo, size, ceil(total, size));
  }

  /**
   * 登记一条限制。
   *
   * <p>这是 9.2.2.1 禁运清单与 16 合规平台清单未交付期间的<b>唯一数据入口</b>：
   * 它不是占位，是真实参与判定的业务数据。上游接口到位后由同步器按同一形状写入，
   * 只需把 {@code restrictionSource} 改成 MODULE_*，表结构与判定逻辑都不动。
   */
  @Transactional
  public InterceptRestriction saveRestriction(InterceptRestrictionInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    String target = input.restrictionTarget().trim().toUpperCase(Locale.ROOT);
    if (input.restrictionKind() == RestrictionKind.SHIPPING_COUNTRY
        && !target.matches("^[A-Z]{2}$")) {
      throw bad(
          "收货国家限制的目标必须是 ISO 3166-1 alpha-2 两位大写国码（收到 "
              + target
              + "）。写成国家全名会让它永远匹配不上 order_address_snapshot.country_code，"
              + "而界面显示「已限制」");
    }
    String reference = nz(input.sourceReference()).trim();
    if (input.restrictionSource() != RestrictionSource.TENANT_MAINTAINED && reference.isEmpty()) {
      throw bad(
          "来源为 " + input.restrictionSource().label() + " 时必须带 sourceReference："
              + "否则「这行是同步器写的」就成了无法追溯的口头声明");
    }
    if (input.effectiveFrom() != null
        && input.effectiveTo() != null
        && input.effectiveTo().isBefore(input.effectiveFrom())) {
      throw bad("生效结束时间不能早于生效开始时间：一条永远不生效的限制比报错更糟（看起来配过了）");
    }
    sql.execute(
        "insert into order_intercept_restriction(id,tenant_id,restriction_kind,restriction_target,"
            + "restriction_effect,restriction_source,source_reference,reason,effective_from,"
            + "effective_to,enabled,created_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,true,now())"
            + " on conflict (tenant_id,restriction_kind,restriction_target) do update"
            + " set restriction_effect=excluded.restriction_effect,"
            + "restriction_source=excluded.restriction_source,"
            + "source_reference=excluded.source_reference,reason=excluded.reason,"
            + "effective_from=excluded.effective_from,effective_to=excluded.effective_to,"
            + "enabled=true,created_at=now()",
        UUID.randomUUID(),
        currentTenant(),
        input.restrictionKind().name(),
        target,
        input.restrictionEffect().name(),
        input.restrictionSource().name(),
        reference,
        nz(input.reason()),
        input.effectiveFrom(),
        input.effectiveTo());
    return restrictionRow(
        sql.fetchOne(
            "select * from order_intercept_restriction where tenant_id = "
                + TENANT
                + " and restriction_kind = ? and restriction_target = ?",
            input.restrictionKind().name(),
            target));
  }

  // ================================================================ 成本基线

  /** 成本基线列表（真分页 + 真搜索）。 */
  public InterceptSkuCostPage pageSkuCosts(InterceptSkuCostQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = " + TENANT);
    if (notBlank(query.keyword())) {
      where.add("(sku_code ilike ? or sku_name ilike ? or note ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
      binds.add(k);
    }
    if (query.skuId() != null) {
      where.add("sku_id = ?");
      binds.add(query.skuId());
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) c from order_intercept_sku_cost c" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<InterceptSkuCost> items =
        sql.fetch(
                "select c.* from order_intercept_sku_cost c"
                    + sqlWhere
                    + " order by c.sku_code, c.captured_at desc, c.id desc limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(OrderInterceptService::skuCostRow);
    return new InterceptSkuCostPage(items, total, pageNo, size, ceil(total, size));
  }

  /**
   * 登记一条成本基线。
   *
   * <p>「采集时刻」必填且不允许留成登记时刻：换供应商、涨价都会改这一列，
   * 而历史拦截结论必须能复算 —— 拿登记时刻会让三个月前的成本看起来是今天的。
   */
  @Transactional
  public InterceptSkuCost saveSkuCost(InterceptSkuCostInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    sql.execute(
        "insert into order_intercept_sku_cost(id,tenant_id,sku_id,sku_code,sku_name,unit_cost,"
            + "currency,captured_at,note,created_at)"
            + " values(?,?,?,?,?,?,?,?::timestamptz,?,now())"
            + " on conflict (tenant_id,sku_id,captured_at) do update"
            + " set sku_code=excluded.sku_code,sku_name=excluded.sku_name,"
            + "unit_cost=excluded.unit_cost,currency=excluded.currency,note=excluded.note",
        UUID.randomUUID(),
        currentTenant(),
        input.skuId(),
        nz(input.skuCode()),
        nz(input.skuName()),
        money(input.unitCost()),
        input.currency().trim().toUpperCase(Locale.ROOT),
        input.capturedAt(),
        nz(input.note()));
    return skuCostRow(
        sql.fetchOne(
            "select * from order_intercept_sku_cost where tenant_id = "
                + TENANT
                + " and sku_id = ? and captured_at = ?::timestamptz",
            input.skuId(),
            input.capturedAt()));
  }

  // ================================================================ 检查

  /**
   * 对一张订单跑一次拦截检查。
   *
   * <p>写入顺序固定为「先插头 → 再写八行明细」：V153 的合计触发器挂在<b>第八行落下</b>时，
   * 头插入那一刻一行明细都还没有。
   *
   * <p>判定基准时刻恒等于订单的 {@code submitted_at}，不是 {@code now()} ——
   * 同一张单重复检查一万次，八项结论逐位相同。成本基线与限制清单的生效期也相对这个时刻判，
   * 且排除基准时刻之后采集/生效的记录。
   */
  @Transactional
  public InterceptCheckSaved assess(InterceptCheckInput input, UUID actorId) {
    if (input == null || input.orderId() == null) {
      throw bad("必须指明要检查的订单（orderId）");
    }
    InterceptPolicy policy = requirePolicy();
    OrderFacts facts = orderFacts(input.orderId());
    UUID tenant = currentTenant();
    UUID checkId = UUID.randomUUID();

    // 头行先按「全缺」插入：CHECK (flagged + cleared + missing = 8) 要求插入时就自洽，
    // 而真正的数字由第八行落下时的触发器算完再回填。
    sql.execute(
        "insert into order_intercept_check(id,tenant_id,order_id,order_no,order_source,buyer_key,"
            + "order_currency,order_amount,order_submitted_at,shipping_country_code,policy_id,"
            + "policy_version,policy_name,assessed_reference_at,missing_count,"
            + "recommended_disposition,manual_review_required,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?::timestamptz,8,"
            + "'MANUAL_REVIEW',true,?,now())",
        checkId,
        tenant,
        facts.orderId(),
        facts.orderNo(),
        facts.orderSource(),
        facts.buyerKey(),
        facts.currency(),
        facts.amount(),
        facts.submittedAt(),
        facts.countryCode(),
        policy.id(),
        policy.version(),
        policy.policyName(),
        facts.submittedAt(),
        actorId);

    List<DraftFinding> drafts =
        List.of(
            addressIncomplete(facts),
            addressDeliverability(),
            blacklistedBuyer(facts),
            duplicateOrder(policy, facts),
            belowCostDiscount(policy, facts),
            suspectedFraud(policy, facts),
            countryRestricted(facts),
            platformCompliance(facts));

    for (DraftFinding draft : drafts) {
      sql.execute(
          "insert into order_intercept_finding(id,tenant_id,check_id,check_code,status,severity,"
              + "evidence_source,observed_value,threshold_value,match_count,evidence_reference,"
              + "explanation,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,now())",
          UUID.randomUUID(),
          tenant,
          checkId,
          draft.code().name(),
          draft.status().name(),
          draft.severity().name(),
          draft.evidenceSource().name(),
          draft.observedValue() == null ? null : qty(draft.observedValue()),
          draft.thresholdValue() == null ? null : qty(draft.thresholdValue()),
          draft.matchCount(),
          nz(draft.evidenceReference()),
          draft.explanation());
    }

    InterceptDetail detail = check(checkId);
    var head = detail.check();
    return new InterceptCheckSaved(
        head,
        detail.findings(),
        "本次检查按策略「"
            + policy.policyName()
            + "」第 "
            + policy.version()
            + " 版判定，基准时刻取订单提交时刻 "
            + facts.submittedAt()
            + "（不是当前时间）：同一张单重复检查结果恒定。"
            + "八项命中 "
            + head.flaggedCount()
            + " 项（其中 BLOCK 级 "
            + head.blockedCount()
            + " 项）、核对通过 "
            + head.clearedCount()
            + " 项、缺参照或上游未接通 "
            + head.missingCount()
            + " 项 → 建议处置「"
            + head.recommendedDisposition().label()
            + "」。"
            + "**这是建议不是结论**：拦截不自动取消，即使建议取消也必须人工处置留痕后才会有最终处置。");
  }

  /** 拦截清单列表（真分页 + 真搜索），带不受分页影响的汇总。 */
  public InterceptCheckPage pageChecks(InterceptCheckQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("c.tenant_id = " + TENANT);
    if (notBlank(query.keyword())) {
      where.add("(c.order_no ilike ? or c.policy_name ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    if (notBlank(query.recommendedDisposition())) {
      where.add("c.recommended_disposition = ?");
      binds.add(query.recommendedDisposition().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.checkCode())) {
      // 按命中项筛：走 EXISTS 而不是 join，否则一张单命中两项会被数成两行。
      where.add(
          "exists (select 1 from order_intercept_finding f where f.tenant_id = c.tenant_id"
              + " and f.check_id = c.id and f.check_code = ?)");
      binds.add(query.checkCode().trim().toUpperCase(Locale.ROOT));
    }
    if (Boolean.TRUE.equals(query.pendingDispositionOnly())) {
      where.add("c.manual_review_required and c.final_disposition is null");
    }
    if (notBlank(query.buyerKey())) {
      where.add("c.buyer_key = ?");
      binds.add(query.buyerKey().trim().toLowerCase(Locale.ROOT));
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) c2 from order_intercept_check c" + sqlWhere, binds.toArray())
            .get("c2", Long.class);
    // ⚠️ 平手用 order_no 再用 id 打破，**不能**只用 id：uuid 是随机的，
    // 同一秒检查的两张单会让「谁在前面」每次刷新都不一样。
    List<InterceptCheck> items =
        sql.fetch(
                "select c.* from order_intercept_check c"
                    + sqlWhere
                    + " order by c.assessed_reference_at desc, c.order_no, c.id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(OrderInterceptService::checkRow);
    return new InterceptCheckPage(
        items, total, pageNo, size, ceil(total, size), summaryOf(sqlWhere, binds));
  }

  /** 检查详情：头 + 八行命中明细 + 人工处置留痕。跨租户在 RLS 下查不到 → 404。 */
  public InterceptDetail check(UUID checkId) {
    Record head =
        sql.fetchOne(
            "select * from order_intercept_check where tenant_id = " + TENANT + " and id = ?",
            checkId);
    if (head == null) {
      throw notFound("拦截检查不存在或不属于本租户（" + checkId + "）");
    }
    List<InterceptFinding> findings =
        sql.fetch(
                "select * from order_intercept_finding where tenant_id = "
                    + TENANT
                    + " and check_id = ?",
                checkId)
            .map(OrderInterceptService::findingRow);
    // ⚠️ 按 check_code 的**枚举声明顺序**排，不按字典序：
    // 详情页要按「地址 → 买家 → 订单特征 → 合规」的判读顺序呈现，
    // 而字典序会把 PLATFORM_COMPLIANCE 排到最前面，客服读不出这条判读链。
    findings =
        findings.stream()
            .sorted(
                java.util.Comparator.comparingInt(f -> f.checkCode().ordinal()))
            .toList();
    return new InterceptDetail(checkRow(head), findings, dispositionOf(checkId));
  }

  // ================================================================ 人工处置

  /**
   * 提交人工处置 —— <b>本模块里唯一能产生「取消」的地方</b>。
   *
   * <p>蓝图加粗句「拦截不自动取消，默认转人工」在这一行上落地：
   * 拦截结果无论多严重都产不出 finalDisposition，必须先有人在这里落一笔。
   *
   * <p>三种结论都会回填最终处置：RELEASE（人工确认这是误拦）、HOLD（挂起等材料）、
   * CANCEL（人工确认取消）。「升级合规」用 {@code ESCALATED_COMPLIANCE} 表达，
   * 它<b>配的是 HOLD 而不是 CANCEL</b> —— 把「已升级」当成「已取消」是一起真实的客诉来源。
   */
  @Transactional
  public InterceptDispositionSaved disposition(
      UUID checkId, InterceptDispositionInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    if (input.reasonCode() == DispositionReasonCode.FALSE_POSITIVE
        && input.decision() != FinalDisposition.RELEASE) {
      throw bad(
          "原因码「误拦：真实订单被错误命中」与结论「人工确认取消」自相矛盾。"
              + "确实是误拦就应该放行；要取消请用 CONFIRMED_ANOMALY");
    }
    if (input.reasonCode() == DispositionReasonCode.ESCALATED_COMPLIANCE
        && input.decision() == FinalDisposition.CANCEL) {
      throw bad(
          "「升级 16 合规处理」不等于「已取消」：卷 16 未交付期间升级后没有结案方，"
              + "把升级记成取消会让平台侧收到一个无法解释的取消回写。请用 HOLD");
    }
    var existing =
        sql.fetchOne(
            "select id from order_intercept_disposition where tenant_id = "
                + TENANT
                + " and check_id = ?",
            checkId);
    if (existing != null) {
      throw conflict(
          "这份检查已经处置过了。拦截留痕不可覆写 —— 要改结论请重新检查一次"
              + "（那会带上新的成本基线与新的人工），而不是把上一次结论改掉。");
    }
    InterceptDetail detail = check(checkId);
    if (!detail.check().manualReviewRequired()) {
      throw conflict(
          "这份检查的建议处置是「"
              + detail.check().recommendedDisposition().label()
              + "」，不需要人工处置，给它补一条处置记录没有意义。");
    }
    sql.execute(
        "insert into order_intercept_disposition(id,tenant_id,check_id,decision,disposed_by,"
            + "disposed_at,reason_code,note) values(?,?,?,?,?,?::timestamptz,?,?)",
        UUID.randomUUID(),
        currentTenant(),
        checkId,
        input.decision().name(),
        actorId,
        OffsetDateTime.now(),
        input.reasonCode().name(),
        nz(input.note()));
    InterceptDetail after = check(checkId);
    return new InterceptDispositionSaved(
        after.check(),
        after.disposition(),
        "处置已留痕（" + input.reasonCode().label() + "），最终处置回填为「"
            + after.check().finalDisposition().label()
            + "」。**这是人工给出的结论** —— 拦截本身只产出建议，不会自己取消订单。");
  }

  // ================================================================ 八项判定

  /** 一项的待落库结果。 */
  private record DraftFinding(
      InterceptCheckCode code,
      FindingStatus status,
      FindingSeverity severity,
      EvidenceSource evidenceSource,
      BigDecimal observedValue,
      BigDecimal thresholdValue,
      int matchCount,
      String evidenceReference,
      String explanation) {}

  /**
   * 项一：地址信息不全 —— 真 SQL 判定，不是登记来的。
   *
   * <p>全部由收货地址快照的列推出（不查任何外部服务）：
   * MISSING_POSTCODE / MISSING_PHONE / PO_BOX_ONLY / INCOMPLETE_ADDRESS / RESERVED_POSTCODE。
   * 判定结果逐项写进 explanation，客服能念出是哪一种异常。
   *
   * <p>⚠️ 与 9.2.1.2（V149）的关系：V149 把地址异常做成**租户可配置的条件**（运营自己定阈值），
   * 本模块把它做成**固定的命中项**（拦截清单必须有这一项，且不因租户没配规则而消失）。
   * 两处的异常码词表一致，但代码刻意各写一份 —— V149 是别的车道的文件所有权。
   */
  private DraftFinding addressIncomplete(OrderFacts facts) {
    if (!facts.hasShippingAddress()) {
      return missing(
          InterceptCheckCode.ADDRESS_INCOMPLETE,
          EvidenceSource.DERIVED_FROM_ORDERS,
          "这一单没有收货地址快照：V99 只对 SUBMITTED 单要求收货地址，"
              + "没有快照时地址信息完整性无从谈起");
    }
    List<String> issues = new ArrayList<>();
    if (!notBlank(facts.postcode())) {
      issues.add("MISSING_POSTCODE（缺邮编）");
    }
    if (!notBlank(facts.phone())) {
      issues.add("MISSING_PHONE（缺联系电话）");
    }
    if (!notBlank(facts.company())
        && notBlank(facts.addressLine1())
        && PO_BOX.matcher(facts.addressLine1()).find()) {
      issues.add("PO_BOX_ONLY（只有信箱类地址且无公司名）");
    }
    if (!notBlank(facts.addressLine1())
        || facts.addressLine1().trim().length() < 6
        || (!notBlank(facts.city()) && !notBlank(facts.district()))) {
      issues.add("INCOMPLETE_ADDRESS（地址行过短，或省市都空）");
    }
    if (notBlank(facts.postcode()) && REPEATED_DIGITS.matcher(facts.postcode().trim()).matches()) {
      issues.add("RESERVED_POSTCODE（邮编是同一个数字重复，如 00000）");
    }
    if (issues.isEmpty()) {
      return cleared(
          InterceptCheckCode.ADDRESS_INCOMPLETE,
          EvidenceSource.DERIVED_FROM_ORDERS,
          BigDecimal.valueOf(0),
          BigDecimal.valueOf(0),
          "",
          "收货地址信息完整：邮编 "
              + nz(facts.postcode())
              + "、联系电话 "
              + nz(facts.phone())
              + "、地址行长度 "
              + nz(facts.addressLine1()).trim().length()
              + "。注意这只说明「信息齐全」，不说明「送得到」—— 后者归 ADDRESS_DELIVERABILITY");
    }
    return flagged(
        InterceptCheckCode.ADDRESS_INCOMPLETE,
        FindingSeverity.WARN,
        EvidenceSource.DERIVED_FROM_ORDERS,
        BigDecimal.valueOf(issues.size()),
        BigDecimal.ZERO,
        issues.size(),
        String.join("、", issues),
        "命中 " + issues.size() + " 类地址信息问题：" + String.join("、", issues)
            + "。建议转人工核对，不要直接取消 —— 地址不全多数是录错，不是恶意");
  }

  /**
   * 项二：地址可投递性 —— <b>上游未交付，如实报不可用</b>。
   *
   * <p>9.2.2.1 地址与支付校验在集成头未开发，本服务没有任何可读的地址校验结果。
   * 这里落 UPSTREAM_UNAVAILABLE 而不是 CLEARED：「没有校验过」与「校验过且通过」是两件事，
   * 把前者报成后者会让界面显示「地址正常」，而实际上这一项**根本没量过**。
   */
  private DraftFinding addressDeliverability() {
    return new DraftFinding(
        InterceptCheckCode.ADDRESS_DELIVERABILITY,
        FindingStatus.UPSTREAM_UNAVAILABLE,
        FindingSeverity.INFO,
        EvidenceSource.UPSTREAM_NOT_DELIVERED,
        null,
        null,
        0,
        "",
        "9.2.2.1 地址与支付校验未开发：本服务读不到禁运国家清单与可投递性校验结果，"
            + "本项判不了（**不是「地址没问题」**）。它计入 missingCount，"
            + "在 ASSESS_KNOWN_ONLY 下不阻断放行、在 BLOCK_DECISION 下阻断。"
            + "地址**信息不全**由 ADDRESS_INCOMPLETE 真算，不受这一项缺口影响");
  }

  /**
   * 项三：黑名单买家 —— 读 9.2.1.2 的租户自维护档案。
   *
   * <p>先按身份键查、再按邮箱反查（同一个人的姓名拼写可能不同），与 9.2.1.2 同一手法。
   *
   * <p>⚠️ <b>没有档案 ≠ 不是黑名单</b>：无档案落 NO_REFERENCE 而不是 CLEARED。
   * 把「没登记过」当成「核验过、干净」会让合规黑名单形同虚设 ——
   * 而卷 16 合规的正式黑名单到位后，这一条正是它的落点。
   */
  private DraftFinding blacklistedBuyer(OrderFacts facts) {
    if (facts.buyerKey() == null) {
      return missing(
          InterceptCheckCode.BLACKLISTED_BUYER,
          EvidenceSource.MODULE_9_2_1_2_BUYER_PROFILE,
          "这一单没有收货地址快照，算不出买家身份键，黑名单无从比对");
    }
    Record profile =
        sql.fetchOne(
            "select id,buyer_key,blacklisted,blacklist_reason,blacklisted_at,buyer_level"
                + " from order_review_buyer_profile where tenant_id = ?"
                + " and (buyer_key = ? or lower(buyer_email) = lower(?))"
                + " order by (buyer_key = ?) desc limit 1",
            currentTenant(),
            facts.buyerKey(),
            nz(facts.email()),
            facts.buyerKey());
    if (profile == null) {
      return missing(
          InterceptCheckCode.BLACKLISTED_BUYER,
          EvidenceSource.MODULE_9_2_1_2_BUYER_PROFILE,
          "order_review_buyer_profile 中没有 buyer_key=" + facts.buyerKey() + " 的档案："
              + "**未登记 ≠ 核验过、干净**。卷 16 合规未交付，正式黑名单到位后本项会自动接通");
    }
    String id = profile.get("id", UUID.class).toString();
    String level = nz(profile.get("buyer_level", String.class));
    if (Boolean.TRUE.equals(profile.get("blacklisted", Boolean.class))) {
      return new DraftFinding(
          InterceptCheckCode.BLACKLISTED_BUYER,
          FindingStatus.FLAGGED,
          FindingSeverity.WARN,
          EvidenceSource.MODULE_9_2_1_2_BUYER_PROFILE,
          BigDecimal.ONE,
          BigDecimal.ZERO,
          1,
          id,
          "买家在 9.2.1.2 买家档案里已被拉黑（等级 "
              + level
              + "，拉黑于 "
              + profile.get("blacklisted_at", OffsetDateTime.class)
              + "），理由："
              + nz(profile.get("blacklist_reason", String.class))
              + "。建议转人工核对，不自动取消 —— 加粗句说的就是这一类："
              + "误拦真实订单的代价通常高于人工看一眼");
    }
    return cleared(
        InterceptCheckCode.BLACKLISTED_BUYER,
        EvidenceSource.MODULE_9_2_1_2_BUYER_PROFILE,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        id,
        "买家档案存在且未被拉黑（等级 " + level + "）。依据是租户自维护的 9.2.1.2 档案，"
            + "不是卷 16 合规的正式黑名单");
  }

  /**
   * 项四：重复下单 —— 真 SQL：同 buyer_key 窗口内、<b>本单之前</b>的已提交笔数。
   *
   * <p>⚠️ 「本单之前」是硬口径：把本单算进重复笔数会让**每一张单都至少命中一次**，
   * 而一单从不与自己是重复单。
   */
  private DraftFinding duplicateOrder(InterceptPolicy policy, OrderFacts facts) {
    if (facts.buyerKey() == null) {
      return missing(
          InterceptCheckCode.DUPLICATE_ORDER,
          EvidenceSource.DERIVED_FROM_ORDERS,
          "这一单没有收货地址快照，算不出买家身份键，重复下单无从比对");
    }
    BigDecimal count =
        sql.fetchOne(
                "select count(*) c from sales_order o"
                    + " join order_address_snapshot a on a.tenant_id = o.tenant_id"
                    + "   and a.order_id = o.id and a.address_role = 'SHIPPING'"
                    + " where o.tenant_id = ? and o.status = 'SUBMITTED' and o.id <> ?"
                    + "   and o.submitted_at < ?::timestamptz"
                    + "   and o.submitted_at >= (?::timestamptz - (? * interval '1 hour'))"
                    + "   and md5(concat_ws(E'\\x1f', a.recipient_name, a.recipient_phone,"
                    + "            a.recipient_email)) = ?",
                currentTenant(),
                facts.orderId(),
                facts.submittedAt(),
                facts.submittedAt(),
                policy.duplicateWindowHours(),
                facts.buyerKey())
            .get("c", BigDecimal.class);
    int n = count == null ? 0 : count.intValue();
    if (n >= policy.duplicateMaxOrders()) {
      return new DraftFinding(
          InterceptCheckCode.DUPLICATE_ORDER,
          FindingStatus.FLAGGED,
          FindingSeverity.WARN,
          EvidenceSource.DERIVED_FROM_ORDERS,
          count,
          BigDecimal.valueOf(policy.duplicateMaxOrders()),
          1,
          facts.buyerKey(),
          "同一买家在提交时刻前 "
              + policy.duplicateWindowHours()
              + " 小时内已提交 "
              + n
              + " 单 ≥ 阈值 "
              + policy.duplicateMaxOrders()
              + " 单。建议转人工核对是否重复下单 —— 重复单常是买家误操作，也可能是薅羊毛，"
              + "两种处置完全不同，机器分不出来"
              + "（不含本单：把本单算进去会让每一张单都至少命中一次，"
              + "而一单从不与自己是重复单）");
    }
    return new DraftFinding(
        InterceptCheckCode.DUPLICATE_ORDER,
        FindingStatus.CLEARED,
        FindingSeverity.INFO,
        EvidenceSource.DERIVED_FROM_ORDERS,
        count,
        BigDecimal.valueOf(policy.duplicateMaxOrders()),
        0,
        facts.buyerKey(),
        "同一买家在提交时刻前 " + policy.duplicateWindowHours() + " 小时内另有 " + n
            + " 单已提交，低于阈值 " + policy.duplicateMaxOrders() + " 单。"
            + "（不含本单：把本单算进去会让每张单都命中）");
  }

  /**
   * 项五：异常折扣（低于成本）—— 真 SQL：逐行净额单价 vs 租户成本基线。
   *
   * <p>⚠️ 折扣金额是<b>负数</b>（V99 的 {@code sales_order_line_discount_is_non_positive} 强制
   * {@code <= 0}），所以净额是 {@code line_amount + discount_amount}。写成减号会让每一行
   * 都算成高价，于是这一项永远不会命中 —— 而一个永不命中的检查比没有检查更糟，
   * 因为界面会显示「无异常折扣」，运营据此认为这一项量过了。
   *
   * <p>⚠️ 币种是硬口径：成本基线币种与订单币种不一致的行**不参与比较**（落 NO_REFERENCE）。
   */
  private DraftFinding belowCostDiscount(InterceptPolicy policy, OrderFacts facts) {
    BigDecimal tolerance = policy.belowCostToleranceRate();
    Record r =
        sql.fetchOne(
            "select count(*) as line_count,"
                + " count(c.unit_cost) as costed_count,"
                + " count(*) filter (where c.unit_cost is not null"
                + "   and (l.line_amount + l.discount_amount) / l.quantity"
                + "       < c.unit_cost * (1 + ?::numeric)) as matched_count,"
                + " coalesce(string_agg(l.sku_code, ',' order by l.line_no)"
                + "   filter (where c.unit_cost is not null"
                + "     and (l.line_amount + l.discount_amount) / l.quantity"
                + "         < c.unit_cost * (1 + ?::numeric)), '') as matched_skus,"
                + " min((l.line_amount + l.discount_amount) / l.quantity)"
                + "   filter (where c.unit_cost is not null"
                + "     and (l.line_amount + l.discount_amount) / l.quantity"
                + "         < c.unit_cost * (1 + ?::numeric)) as worst_net,"
                + " min(c.unit_cost)"
                + "   filter (where c.unit_cost is not null"
                + "     and (l.line_amount + l.discount_amount) / l.quantity"
                + "         < c.unit_cost * (1 + ?::numeric)) as worst_cost"
                + " from sales_order_line l"
                + " left join lateral ("
                + "   select k.unit_cost from order_intercept_sku_cost k"
                + "    where k.tenant_id = l.tenant_id and k.sku_id = l.sku_id"
                // ⚠️ 这两条 WHERE 上刻意**不写** SQL 行注释（--）：jOOQ 解析 ? 占位符时
                // 不理解注释，注释会把它后面的 ? 一起吞掉，于是绑定参数与占位符数量对不上，
                // 报错是 BadSqlGrammarException（bad SQL grammar），与真正的原因毫无关系。
                + "      and k.currency = ?"
                + "      and k.captured_at <= ?::timestamptz"
                + "    order by k.captured_at desc, k.id desc limit 1) c on true"
                + " where l.tenant_id = ? and l.order_id = ?",
            tolerance,
            tolerance,
            tolerance,
            tolerance,
            facts.currency(),
            facts.submittedAt(),
            currentTenant(),
            facts.orderId());
    int lineCount = orZero(r.get("line_count", Integer.class));
    int costedCount = orZero(r.get("costed_count", Integer.class));
    int matched = orZero(r.get("matched_count", Integer.class));
    if (lineCount == 0) {
      return missing(
          InterceptCheckCode.BELOW_COST_DISCOUNT,
          EvidenceSource.DERIVED_FROM_ORDERS,
          "这一单没有任何订单行，无法逐行比成本");
    }
    if (costedCount == 0) {
      return missing(
          InterceptCheckCode.BELOW_COST_DISCOUNT,
          EvidenceSource.TENANT_MAINTAINED,
          "本单 "
              + lineCount
              + " 个订单行都**没有匹配到成本基线**（sku_id 无记录，或基线币种 "
              + facts.currency()
              + " 与订单币种不一致，或基线采集时刻晚于订单提交时刻）："
              + "卷 05 定价未交付，本服务没有成本来源，成本基线靠租户维护。"
              + "**没量到成本 ≠ 成本正常**");
    }
    if (matched > 0) {
      BigDecimal worstNet = r.get("worst_net", BigDecimal.class);
      BigDecimal worstCost = r.get("worst_cost", BigDecimal.class);
      return new DraftFinding(
          InterceptCheckCode.BELOW_COST_DISCOUNT,
          FindingStatus.FLAGGED,
          FindingSeverity.WARN,
          EvidenceSource.TENANT_MAINTAINED,
          worstNet,
          worstCost,
          matched,
          r.get("matched_skus", String.class),
          matched
              + " 个订单行的净额单价低于成本（最差一行净额 "
              + money(worstNet)
              + " < 成本 "
              + money(worstCost)
              + "，容忍度 "
              + tolerance
              + " 即允许亏 "
              + tolerance.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP)
              + "%）；命中 SKU："
              + nz(r.get("matched_skus", String.class))
              + "。建议转人工核对是清仓促销还是配错价 —— 低于成本也可能是刻意让利，"
              + "机器分不出来，所以只给建议");
    }
    return new DraftFinding(
        InterceptCheckCode.BELOW_COST_DISCOUNT,
        FindingStatus.CLEARED,
        FindingSeverity.INFO,
        EvidenceSource.TENANT_MAINTAINED,
        null,
        null,
        0,
        "",
        costedCount
            + " 个订单行都有可用成本基线，净额单价均未低于成本 ×(1+"
            + tolerance
            + ")"
            + (lineCount > costedCount
                ? "；另有 " + (lineCount - costedCount) + " 行无成本基线未参与比较（已计入缺参照口径）"
                : ""));
  }

  /**
   * 项六：疑似欺诈 —— <b>复用</b> 9.2.2.2 的结论，不重算分数也不绕过它。
   *
   * <p>V150 确立的口径「风控分数不能单独作为拒绝依据，需人工复核」在这里原样保留：
   * 本模块拿 {@code risk_score} 与 {@code recommended_action} 只产出<b>建议</b>；
   * 当 V150 已有<b>人工</b>结论 {@code final_action = 'REJECT'} 时，才按该结论把严重度提到 BLOCK。
   * 本模块自己的最终处置仍然只由 {@link #disposition} 的人工留痕产生。
   */
  private DraftFinding suspectedFraud(InterceptPolicy policy, OrderFacts facts) {
    Record assessment =
        sql.fetchOne(
            "select id,risk_score,recommended_action,final_action,manual_review_required,"
                + "evaluation_status,evaluated_reference_at"
                + " from order_risk_assessment where tenant_id = ? and order_id = ?"
                + " order by evaluated_reference_at desc, id desc limit 1",
            currentTenant(),
            facts.orderId());
    if (assessment == null) {
      return missing(
          InterceptCheckCode.SUSPECTED_FRAUD,
          EvidenceSource.MODULE_9_2_2_2_RISK_ASSESSMENT,
          "该单没有 order_risk_assessment：9.2.2.2 风控校验未对它评估过。"
              + "**未评估 ≠ 低风险**。先跑一次 POST /trade/orders/risk-validation/assessments 再回来");
    }
    String id = assessment.get("id", UUID.class).toString();
    BigDecimal score = assessment.get("risk_score", BigDecimal.class);
    String recommended = nz(assessment.get("recommended_action", String.class));
    String finalAction = assessment.get("final_action", String.class);
    String evaluationStatus = nz(assessment.get("evaluation_status", String.class));
    if ("SCORED".equals(evaluationStatus) && score != null) {
      boolean highScore = score.compareTo(policy.fraudScoreThreshold()) >= 0;
      boolean riskRejected = "REJECT".equals(finalAction);
      if (highScore || riskRejected) {
        return new DraftFinding(
            InterceptCheckCode.SUSPECTED_FRAUD,
            FindingStatus.FLAGGED,
            riskRejected ? FindingSeverity.BLOCK : FindingSeverity.WARN,
            EvidenceSource.MODULE_9_2_2_2_RISK_ASSESSMENT,
            score,
            policy.fraudScoreThreshold(),
            1,
            id,
            "9.2.2.2 风控分 "
                + score
                + "（阈值 "
                + policy.fraudScoreThreshold()
                + "），建议动作 "
                + recommended
                + "，风控最终动作 "
                + (finalAction == null ? "尚无人工结论" : finalAction)
                + "。"
                + (riskRejected
                    ? "风控侧**已有人工结论 REJECT**，本项按该结论定档为 BLOCK"
                    : "风控分数本身不能作为拒绝依据（9.2.2.2 加粗句），"
                        + "所以这里只给建议、严重度为 WARN")
                + "。本模块的最终处置仍需人工处置留痕");
      }
      return new DraftFinding(
          InterceptCheckCode.SUSPECTED_FRAUD,
          FindingStatus.CLEARED,
          FindingSeverity.INFO,
          EvidenceSource.MODULE_9_2_2_2_RISK_ASSESSMENT,
          score,
          policy.fraudScoreThreshold(),
          0,
          id,
          "9.2.2.2 风控分 " + score + "，低于阈值 " + policy.fraudScoreThreshold()
              + "，建议动作 " + recommended + "，最终动作 "
              + (finalAction == null ? "尚无人工结论" : finalAction));
    }
    return missing(
        InterceptCheckCode.SUSPECTED_FRAUD,
        EvidenceSource.MODULE_9_2_2_2_RISK_ASSESSMENT,
        "9.2.2.2 对该单的评估状态是 " + evaluationStatus + "（未出分）："
            + "它按自己的口径判「信号不足不出分」，本项据此落缺参照 —— "
            + "一个残缺的风险分会被读成「量过了，结论是低分」，那正是不该发生的误伤");
  }

  /** 项七：超出收货国家限制 —— 租户可维护的限制清单，BLOCK 档建议取消（仍需人工）。 */
  private DraftFinding countryRestricted(OrderFacts facts) {
    if (facts.countryCode() == null) {
      return missing(
          InterceptCheckCode.COUNTRY_RESTRICTED,
          EvidenceSource.TENANT_MAINTAINED,
          "这一单没有收货地址快照，收货国无从取得，限制清单无从比对");
    }
    Record restriction = activeRestriction(RestrictionKind.SHIPPING_COUNTRY, facts.countryCode(), facts);
    if (restriction == null) {
      return new DraftFinding(
          InterceptCheckCode.COUNTRY_RESTRICTED,
          FindingStatus.CLEARED,
          FindingSeverity.INFO,
          EvidenceSource.TENANT_MAINTAINED,
          null,
          null,
          0,
          facts.countryCode(),
          "收货国 " + facts.countryCode() + " 不在生效中的限制清单内。"
              + "（依据是租户自维护清单；9.2.2.1 的正式禁运清单未交付）");
    }
    return restrictionFinding(
        InterceptCheckCode.COUNTRY_RESTRICTED, restriction, facts.countryCode(), "收货国");
  }

  /**
   * 项八：平台合规风险 —— 租户可维护的限制清单，<b>平台级</b>。
   *
   * <p>⚠️ 实查 V99：{@code sales_order} <b>没有 shop_code 列</b>，所以本项只能按
   * {@code order_source} 判，<b>店铺级合规风险在本服务无处可判</b>
   * （capabilities 如实报 {@code shopScopeSupported = false}）。
   */
  private DraftFinding platformCompliance(OrderFacts facts) {
    Record restriction =
        activeRestriction(RestrictionKind.PLATFORM_SOURCE, facts.orderSource(), facts);
    if (restriction == null) {
      return new DraftFinding(
          InterceptCheckCode.PLATFORM_COMPLIANCE,
          FindingStatus.CLEARED,
          FindingSeverity.INFO,
          EvidenceSource.TENANT_MAINTAINED,
          null,
          null,
          0,
          facts.orderSource(),
          "订单来源 " + facts.orderSource() + " 不在生效中的平台限制清单内。"
              + "注意这是**平台级**判定：sales_order 没有 shop_code 列，"
              + "店铺级合规风险本服务判不了（capabilities.shopScopeSupported = false）");
    }
    return restrictionFinding(
        InterceptCheckCode.PLATFORM_COMPLIANCE, restriction, facts.orderSource(), "订单来源");
  }

  /** 取当前基准时刻生效的那一条限制（确定性：同目标多条时取创建时刻最新的一条）。 */
  private Record activeRestriction(RestrictionKind kind, String target, OrderFacts facts) {
    return sql.fetchOne(
        "select * from order_intercept_restriction where tenant_id = ?"
            + " and restriction_kind = ? and restriction_target = ? and enabled"
            + " and (effective_from is null or effective_from <= ?::timestamptz)"
            + " and (effective_to is null or effective_to >= ?::timestamptz)"
            + " order by created_at desc, id desc limit 1",
        currentTenant(),
        kind.name(),
        target,
        facts.submittedAt(),
        facts.submittedAt());
  }

  private static DraftFinding restrictionFinding(
      InterceptCheckCode code, Record restriction, String target, String label) {
    RestrictionEffect effect =
        RestrictionEffect.valueOf(restriction.get("restriction_effect", String.class));
    RestrictionSource source =
        RestrictionSource.valueOf(restriction.get("restriction_source", String.class));
    return new DraftFinding(
        code,
        FindingStatus.FLAGGED,
        effect == RestrictionEffect.BLOCK ? FindingSeverity.BLOCK : FindingSeverity.WARN,
        EvidenceSource.TENANT_MAINTAINED,
        null,
        null,
        1,
        target,
        label + " " + target + " 命中限制清单（强度 "
            + effect.label()
            + "，来源 "
            + source.label()
            + "），理由："
            + (notBlank(restriction.get("reason", String.class))
                ? restriction.get("reason", String.class)
                : "未填写理由")
            + (notBlank(restriction.get("source_reference", String.class))
                ? "（来源引用 " + restriction.get("source_reference", String.class) + "）"
                : "")
            + (effect == RestrictionEffect.BLOCK
                ? "。BLOCK 档给出「建议取消」，但**拦截不自动取消**："
                    + "最终处置仍需人工处置留痕后才产生"
                : "。REVIEW 档只给「建议转人工」")
            // 店铺级限定**只对平台来源**成立：收货国限制本来就是国家级判定，
            // 对它说「没有 shop_code 列」是错的。
            + (code == InterceptCheckCode.PLATFORM_COMPLIANCE
                ? "。注意这是**平台级**判定：sales_order 没有 shop_code 列，"
                    + "店铺级合规风险本服务判不了（capabilities.shopScopeSupported = false）"
                : ""));
  }

  // ================================================================ 订单事实

  private record OrderFacts(
      UUID orderId,
      String orderNo,
      String orderSource,
      String currency,
      BigDecimal amount,
      OffsetDateTime submittedAt,
      String buyerKey,
      boolean hasShippingAddress,
      String countryCode,
      String name,
      String phone,
      String email,
      String company,
      String city,
      String district,
      String postcode,
      String addressLine1) {}

  /** 订单事实：一条真 SQL 联查，不是内存里凑出来的对象。 */
  private OrderFacts orderFacts(UUID orderId) {
    Record head =
        sql.fetchOne(
            "select o.id,o.order_no,o.order_source,o.currency,o.grand_total_amount,o.status,"
                + "o.submitted_at,"
                + "a.country_code,a.recipient_name,a.recipient_phone,a.recipient_email,"
                + "a.company_name,a.city,a.district,a.postcode,a.address_line1"
                + " from sales_order o"
                + " left join order_address_snapshot a on a.tenant_id = o.tenant_id"
                + "   and a.order_id = o.id and a.address_role = 'SHIPPING'"
                + " where o.tenant_id = " + TENANT + " and o.id = ?",
            orderId);
    if (head == null) {
      throw notFound(
          "订单不存在或不属于本租户（"
              + orderId
              + "）。拦截只对本租户的订单生效，跨租户订单 id 一律按不存在处理（RLS 下查不到即视为不存在）");
    }
    if ("DRAFT".equals(head.get("status", String.class))
        || head.get("submitted_at", OffsetDateTime.class) == null) {
      throw conflict(
          "订单 "
              + head.get("order_no", String.class)
              + " 还没提交（status=DRAFT）。拦截针对的是已提交进审单池的订单："
                  + "草稿还没定稿，判出来的异常会随录入而变，留痕没有意义");
    }
    String name = head.get("recipient_name", String.class);
    String phone = head.get("recipient_phone", String.class);
    String email = head.get("recipient_email", String.class);
    boolean hasAddress = head.get("country_code", String.class) != null;
    return new OrderFacts(
        head.get("id", UUID.class),
        head.get("order_no", String.class),
        head.get("order_source", String.class),
        head.get("currency", String.class),
        money(head.get("grand_total_amount", BigDecimal.class)),
        head.get("submitted_at", OffsetDateTime.class),
        hasAddress ? buyerKey(name, phone, email) : null,
        hasAddress,
        head.get("country_code", String.class),
        name,
        phone,
        email,
        head.get("company_name", String.class),
        head.get("city", String.class),
        head.get("district", String.class),
        head.get("postcode", String.class),
        head.get("address_line1", String.class));
  }

  /**
   * 买家身份键：与 V149 {@code order_review_buyer_profile.buyer_key} <b>逐字同一公式</b>。
   *
   * <p>必须一致，否则会出现「9.2.1.2 拉黑了这个人、9.2.1.3 判定不是黑名单买家」这种无法排查的分歧
   * —— 而这种分歧在真实场景里就是一次漏拦。
   *
   * <p>⚠️ 刻意<b>不抽公共类</b>：V149 与 V150 是别的车道的文件所有权，本车道改它们会造成
   * 跨车道代码冲突。两处各自写死并互相注释指向，代价是同一段 md5 出现两次，换来并入时零冲突。
   */
  static String buyerKey(String name, String phone, String email) {
    try {
      var digest = java.security.MessageDigest.getInstance("MD5");
      var bytes =
          digest.digest(
              (nz(name) + SEP_CHAR + nz(phone) + SEP_CHAR + nz(email))
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var sb = new StringBuilder();
      for (byte b : bytes) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 不可用（买家身份键依赖它）", e);
    }
  }

  // ================================================================ 明细草稿快捷构造

  private static DraftFinding missing(InterceptCheckCode code, EvidenceSource source, String why) {
    return new DraftFinding(
        code, FindingStatus.NO_REFERENCE, FindingSeverity.INFO, source, null, null, 0, "", why);
  }

  private static DraftFinding cleared(
      InterceptCheckCode code,
      EvidenceSource source,
      BigDecimal observed,
      BigDecimal threshold,
      String reference,
      String explanation) {
    return new DraftFinding(
        code, FindingStatus.CLEARED, FindingSeverity.INFO, source, observed, threshold, 0, reference,
        explanation);
  }

  private static DraftFinding flagged(
      InterceptCheckCode code,
      FindingSeverity severity,
      EvidenceSource source,
      BigDecimal observed,
      BigDecimal threshold,
      int matchCount,
      String reference,
      String explanation) {
    return new DraftFinding(
        code, FindingStatus.FLAGGED, severity, source, observed, threshold, matchCount, reference,
        explanation);
  }

  // ================================================================ 行映射

  private static InterceptPolicy policyRow(Record r) {
    return new InterceptPolicy(
        r.get("id", UUID.class),
        r.get("policy_name", String.class),
        r.get("enabled", Boolean.class),
        r.get("duplicate_window_hours", Integer.class),
        r.get("duplicate_max_orders", Integer.class),
        r.get("below_cost_tolerance_rate", BigDecimal.class),
        r.get("fraud_score_threshold", BigDecimal.class),
        MissingReferencePolicy.valueOf(r.get("missing_reference_policy", String.class)),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static InterceptRestriction restrictionRow(Record r) {
    return new InterceptRestriction(
        r.get("id", UUID.class),
        RestrictionKind.valueOf(r.get("restriction_kind", String.class)),
        r.get("restriction_target", String.class),
        RestrictionEffect.valueOf(r.get("restriction_effect", String.class)),
        RestrictionSource.valueOf(r.get("restriction_source", String.class)),
        r.get("source_reference", String.class),
        r.get("reason", String.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        r.get("enabled", Boolean.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static InterceptSkuCost skuCostRow(Record r) {
    return new InterceptSkuCost(
        r.get("id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("unit_cost", BigDecimal.class),
        r.get("currency", String.class),
        r.get("captured_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static InterceptCheck checkRow(Record r) {
    return new InterceptCheck(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("order_source", String.class),
        r.get("buyer_key", String.class),
        r.get("order_currency", String.class),
        r.get("order_amount", BigDecimal.class),
        r.get("order_submitted_at", OffsetDateTime.class),
        r.get("shipping_country_code", String.class),
        r.get("policy_id", UUID.class),
        r.get("policy_name", String.class),
        r.get("policy_version", Long.class),
        r.get("assessed_reference_at", OffsetDateTime.class),
        r.get("flagged_count", Integer.class),
        r.get("blocked_count", Integer.class),
        r.get("cleared_count", Integer.class),
        r.get("missing_count", Integer.class),
        RecommendedDisposition.valueOf(r.get("recommended_disposition", String.class)),
        r.get("manual_review_required", Boolean.class),
        r.get("final_disposition", String.class) == null
            ? null
            : FinalDisposition.valueOf(r.get("final_disposition", String.class)),
        r.get("disposed_at", OffsetDateTime.class),
        r.get("disposed_by", UUID.class),
        r.get("totals_computed_at", OffsetDateTime.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static InterceptFinding findingRow(Record r) {
    return new InterceptFinding(
        r.get("id", UUID.class),
        InterceptCheckCode.valueOf(r.get("check_code", String.class)),
        FindingStatus.valueOf(r.get("status", String.class)),
        FindingSeverity.valueOf(r.get("severity", String.class)),
        EvidenceSource.valueOf(r.get("evidence_source", String.class)),
        r.get("observed_value", BigDecimal.class),
        r.get("threshold_value", BigDecimal.class),
        r.get("match_count", Integer.class),
        r.get("evidence_reference", String.class),
        r.get("explanation", String.class));
  }

  private InterceptDisposition dispositionOf(UUID checkId) {
    Record r =
        sql.fetchOne(
            "select * from order_intercept_disposition where tenant_id = "
                + TENANT
                + " and check_id = ?",
            checkId);
    return r == null
        ? null
        : new InterceptDisposition(
            r.get("id", UUID.class),
            r.get("check_id", UUID.class),
            FinalDisposition.valueOf(r.get("decision", String.class)),
            r.get("disposed_by", UUID.class),
            r.get("disposed_at", OffsetDateTime.class),
            DispositionReasonCode.valueOf(r.get("reason_code", String.class)),
            r.get("note", String.class));
  }

  // ================================================================ 汇总

  /** 全量汇总（不受筛选与分页影响）。 */
  public InterceptSummary overview() {
    return summaryOf(" where tenant_id = " + TENANT, new ArrayList<>());
  }

  private InterceptSummary summaryOf(String sqlWhere, List<Object> binds) {
    Record r =
        sql.fetchOne(
            "select count(*) total,"
                + " count(*) filter (where manual_review_required and final_disposition is null) pending,"
                + " count(*) filter (where recommended_disposition='RELEASE') released,"
                + " count(*) filter (where recommended_disposition='MANUAL_REVIEW') manual,"
                + " count(*) filter (where recommended_disposition='CANCEL') cancel_suggested,"
                + " count(*) filter (where final_disposition='CANCEL') cancelled,"
                + " count(*) filter (where final_disposition='HOLD') held,"
                + " count(*) filter (where flagged_count>0) flagged_check,"
                + " count(*) filter (where missing_count>0) incomplete_check"
                + " from order_intercept_check c"
                + sqlWhere,
            binds.toArray());
    return new InterceptSummary(
        r.get("total", Long.class),
        r.get("pending", Long.class),
        r.get("released", Long.class),
        r.get("manual", Long.class),
        r.get("cancel_suggested", Long.class),
        r.get("cancelled", Long.class),
        r.get("held", Long.class),
        r.get("flagged_check", Long.class),
        r.get("incomplete_check", Long.class));
  }

  // ================================================================ 工具

  private static int pageSize(int requested) {
    return Math.min(MAX_PAGE_SIZE, Math.max(1, requested <= 0 ? 50 : requested));
  }

  private static int ceil(long total, int size) {
    return (int) Math.ceil(total / (double) size);
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

  private static int orZero(Integer value) {
    return value == null ? 0 : value;
  }

  /** 数量/比率刻度：6 位，HALF_UP（与 DB 的 numeric(19,6) 对齐）。 */
  private static BigDecimal qty(BigDecimal value) {
    return value == null ? null : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  /** 金额刻度：4 位，HALF_UP（与 DB 的 numeric(19,4) 对齐）。 */
  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 阈值刻度：4 位，HALF_UP（与 DB 的 numeric(9,4) 对齐）。 */
  private static BigDecimal score(BigDecimal value) {
    return value == null ? null : value.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  /** 比率刻度（容忍度）：4 位，HALF_UP（与 DB 的 numeric(9,4) 对齐）。 */
  private static BigDecimal rate(BigDecimal value) {
    return value == null ? null : value.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
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
