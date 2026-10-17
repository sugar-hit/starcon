package com.starcon.trade;

import static com.starcon.trade.OrderRiskModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.2.2.2 风控校验：多信号风险打分 + 建议动作 + 人工复核。
 *
 * <p><b>六条实现纪律</b>：
 *
 * <ol>
 *   <li><b>三分是真 SQL，两分是租户维护的信号表</b>：下单频次、金额异常、地址集中度全部
 *       直接查本服务 sales_order 簇现算；买家历史（退款率 / 纠纷率）与设备 IP 关联来自
 *       {@code order_risk_signal} —— 15 客户与 16 合规在集成头分别是「24 个模块只交付 1 个」
 *       与「0 个交付」，且各自独立数据库，按模块边界纪律禁止跨库读表，
 *       所以这两维**只能**由租户登记，登记时强制带来源枚举与采集时刻。
 *   <li><b>分数与建议动作由 DB 触发器实算</b>：服务层只负责算出五个维度各自的原始值与归一化分，
 *       {@code order_risk_apply_totals} 负责归一化、求总分、按阈值判动作。
 *       两处各算一遍的结果迟早会不一致，而「评估上写着一个总分，它与五个组件算出来的不一样」
 *       是无法排查的。
 *   <li><b>分数不能单独拒发</b>：本类里**没有任何一处**写 {@code final_action} ——
 *       改最终动作的唯一入口是 {@link #review}，而它要求一条已认证的人工成员身份。
 *   <li><b>评估基准时刻钉在订单 submitted_at 上</b>，不是 {@code now()}：
 *       同一张单重复评估一万次，得到的分数与建议动作完全一致。
 *   <li><b>每条查询都显式带租户谓词</b>：打分查询是「同一买家」「同一地址」的跨订单宽条件，
 *       漏掉租户谓词就是跨租户归因 —— A 家的风控结论会决定 B 家的货发不发。
 *   <li><b>时间一律 {@code ?::timestamptz} 绑定 OffsetDateTime</b>，绝不用
 *       {@code Timestamp.valueOf(LocalDateTime...)}（那会静默偏 −28800s）。
 * </ol>
 */
@Service
public class OrderRiskService {

  /** 单页最大行数。风控评估是凭据不是流水，但仍设上限防一次拉爆 JVM。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final int QTY_SCALE = 6;
  private static final int MONEY_SCALE = 4;
  private static final int SCORE_SCALE = 4;
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** UUID 文本形状（与 V150 order_risk_signal 的 subject_key CHECK 同一写法）。 */
  private static final java.util.regex.Pattern UUID_SHAPE =
      java.util.regex.Pattern.compile("^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$");

  /** 指纹分隔符：不可见 unit separator（U+001F）。与 9.3.1.2 合单同一口径。 */
  private static final String SEP_SQL = " E'\\x1f' ";

  private static final String TENANT_PREDICATE =
      " nullif(current_setting('app.current_tenant',true),'')::uuid";

  /**
   * 买家指纹的 SQL 表达式：有 catalog 客户档案逻辑 ID 时优先用它，否则退回地址快照 md5。
   *
   * <p>「优先用客户档案 ID」是有讲究的：同一买家换了一次收货地址（换了套房子）指纹就变了，
   * 频次与退款率会被算成两个人。
   */
  private static final String BUYER_FINGERPRINT_SQL =
      " coalesce('P:'||p.customer_party_id::text,"
          + "          'A:'||md5(concat_ws("
          + SEP_SQL
          + ", a.recipient_name, a.recipient_phone, a.recipient_email)))";

  /** 地址指纹：完整地址八段 md5。 */
  private static final String ADDRESS_FINGERPRINT_SQL =
      " md5(concat_ws("
          + SEP_SQL
          + ", a.country_code, a.province, a.city, a.district, a.postcode,"
          + " a.address_line1, a.address_line2))";

  private final DSLContext sql;

  public OrderRiskService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：蓝图的上游写的是「15 客户 + 16 合规」，而这两卷在集成头
   * 分别是 24 个 L4 模块里只交付了 1 个（crm 服务的独立库）和 0 个。
   * **哪几维真的接通、哪几维是租户自己维护的必须由接口说**，
   * 而不是让调用方从 404 或空数组里猜哪一环没通。
   */
  public RiskCapabilities capabilities() {
    boolean configured = ruleOrNull() != null;
    return new RiskCapabilities(
        List.of(
            new RiskCapabilityDimension(
                "BUYER_HISTORY",
                RiskDimension.BUYER_HISTORY.label(),
                "15 客户行为画像（退款率 / 纠纷率）",
                false,
                "order_risk_rule.weight_buyer_history",
                "卷 15 的 24 个 L4 模块在集成头**只交付了 1 个**（15.4.1.1 知识库，"
                    + "crm 服务独立库 starcon_crm），且按模块边界纪律禁止跨服务 SQL。"
                    + "本维因此落在租户可维护的 order_risk_signal（REFUND_RATE / DISPUTE_RATE），"
                    + "两码都采集齐才算分，缺一码判 MISSING_SIGNAL 并点名缺哪一码 —— "
                    + "把没量过的纠纷率当成 0 是本模块最不该犯的错。"),
            new RiskCapabilityDimension(
                "DEVICE_IP_LINK",
                RiskDimension.DEVICE_IP_LINK.label(),
                "16 合规关联风险（设备 / IP）",
                false,
                "order_risk_rule.weight_device_ip_link",
                "卷 16 的 24 个 L4 模块在集成头**全部未交付**，连服务都还没有。"
                    + "本维落在租户可维护的 order_risk_signal（DEVICE_IP_LINK_COUNT / "
                    + "SHARED_DEVICE_COUNT / SHARED_IP_COUNT），三码都采集齐才算分。"),
            new RiskCapabilityDimension(
                "ORDER_FREQUENCY",
                RiskDimension.ORDER_FREQUENCY.label(),
                "订单聚合特征（本服务 sales_order 簇，真 SQL）",
                true,
                "order_risk_rule.weight_order_frequency",
                null),
            new RiskCapabilityDimension(
                "AMOUNT_ANOMALY",
                RiskDimension.AMOUNT_ANOMALY.label(),
                "订单聚合特征（本服务 sales_order 簇，真 SQL）",
                true,
                "order_risk_rule.weight_amount_anomaly",
                "同币种硬口径：本服务没有任何汇率来源（10 财务未交付），"
                    + "拿 USD 与 EUR 比「金额异常」是纯噪声，所以历史均单只在**同币种**内算；"
                    + "同币种历史不足 rule.amount_anomaly_min_orders 笔时判 INSUFFICIENT_HISTORY，"
                    + "不拿别的币种凑。"),
            new RiskCapabilityDimension(
                "ADDRESS_CONCENTRATION",
                RiskDimension.ADDRESS_CONCENTRATION.label(),
                "订单聚合特征（本服务 order_address_snapshot，真 SQL）",
                true,
                "order_risk_rule.weight_address_concentration",
                "地址指纹用**收货地址快照**现算 md5（与 9.3.1.2 合单同一口径）："
                    + "V99 已用 guard_sales_order_snapshot_frozen 把 SUBMITTED 后的地址快照冻住，"
                    + "「这几单是不是寄到同一个地方」要判的是下单当时的事实，不是现在的地址。")),
        new RiskCapabilityDownstream(
            "9.2.1.3",
            "审单拦截",
            false,
            "本模块**只输出建议动作**（放行 / 人工 / 拒发），刻意不建 9.2.1.3 的拦截表 —— "
                + "那是它自己的职责。拦截落地后读 order_risk_assessment 的 recommended_action "
                + "与 final_action 即可：前者是分数给的建议，后者是人工给的结论，"
                + "两者分开存就是为了让下游无法「只看分数就拒发」。"),
        configured,
        false,
        "五维权重合计 CHECK = 1.0000（order_risk_rule 强制，与 9.3.1.2 仓配五维权重同一手法）",
        "① 五个维度各自算归一化分（0..100）；② 贡献 = 权重 × 归一化分（DB 侧校验）；"
            + "③ 总分 = Σ贡献 ÷ Σ已评分维度的权重，round 到 4 位；"
            + "④ 总分 ≥ hold_shipment_threshold → 建议拒发；否则 ≥ manual_review_threshold → 建议人工；否则放行。"
            + "缺失维度不参与分母（否则总分会被稀释成一个看起来安全的低分，等于放行了一个没人看过的单）；"
            + "一个维度都没评上时不出分（risk_score = null），只给「建议人工」。",
        "上游缺口：15 客户（24 个 L4 只交付 15.4.1.1，且在 crm 独立库）、"
            + "16 合规（24 个 L4 全部未交付）。下游缺口：9.2.1.3 审单拦截（未交付）。"
            + "本模块对这两处都不假装接通：信号落成带来源枚举的租户可维护表，"
            + "跨服务读表一律禁止（crossServiceReadAvailable = false）。");
  }

  // ================================================================ 规则

  /** 风控规则读取。没登记过就是 404 —— 不是一份带默认值的规则。 */
  public RiskRule rule() {
    var rule = ruleOrNull();
    if (rule == null) {
      throw notFound(
          "本租户尚未登记风控规则。风控分会影响一整批订单的发货节奏，"
              + "本模块不提供隐含默认值：请先 PUT /trade/orders/risk-validation/rule 登记规则。");
    }
    return rule;
  }

  private RiskRule ruleOrNull() {
    Record r = sql.fetchOne("select * from order_risk_rule");
    return r == null ? null : ruleRow(r);
  }

  private RiskRule requireRule() {
    var rule = ruleOrNull();
    if (rule == null) {
      throw conflict(
          "本租户尚未登记风控规则。风控分会影响一整批订单的发货节奏，"
              + "本模块不提供隐含默认值：请先 PUT /trade/orders/risk-validation/rule 登记规则。");
    }
    if (!rule.enabled()) {
      throw conflict(
          "本租户的风控规则「" + rule.ruleName() + "」处于停用状态："
              + "停用规则继续打分等于让运营以为风控还在跑。请先 PUT /rule 把 enabled 打开。");
    }
    return rule;
  }

  /** 登记 / 更新风控规则（upsert，一租户一条）。权重合计 = 1.0000 由 DB CHECK 兜底。 */
  @Transactional
  public RiskRule saveRule(RiskRuleInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    BigDecimal sum =
        input.weightBuyerHistory()
            .add(input.weightDeviceIpLink())
            .add(input.weightOrderFrequency())
            .add(input.weightAmountAnomaly())
            .add(input.weightAddressConcentration());
    if (sum.compareTo(BigDecimal.ONE) != 0) {
      throw bad(
          "五维权重合计必须恰好等于 1.0000，当前是 "
              + sum.setScale(SCORE_SCALE, RoundingMode.HALF_UP)
              + "。权重不等于 1 时「总分」这个数没有意义："
              + "全部维度都拉满却只得 0.8 分，会让人以为风险被稀释过了。");
    }
    if (input.holdShipmentThreshold().compareTo(input.manualReviewThreshold()) <= 0) {
      throw bad(
          "建议拒发阈值必须高于建议人工阈值（当前 "
              + input.manualReviewThreshold()
              + " / "
              + input.holdShipmentThreshold()
              + "）。两者相等时同一张单会同时落进「人工」与「拒发」两档，"
              + "而这两档的下游处置完全不同。");
    }
    UUID tenant = currentTenant();
    var existing = sql.fetchOne("select id from order_risk_rule where tenant_id = ?", tenant);
    if (existing == null) {
      UUID id = UUID.randomUUID();
      sql.execute(
          "insert into order_risk_rule(id,tenant_id,rule_name,enabled,"
              + "weight_buyer_history,weight_device_ip_link,weight_order_frequency,"
              + "weight_amount_anomaly,weight_address_concentration,"
              + "manual_review_threshold,hold_shipment_threshold,"
              + "frequency_window_hours,frequency_max_orders,device_link_max_links,"
              + "amount_anomaly_ratio,amount_anomaly_min_orders,"
              + "address_concentration_window_hours,address_concentration_max_orders,"
              + "signal_max_age_hours,missing_signal_policy,version,created_at,updated_at)"
              + " values(?,?,?,true,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,now(),now())",
          id,
          tenant,
          input.ruleName().trim(),
          score(input.weightBuyerHistory()),
          score(input.weightDeviceIpLink()),
          score(input.weightOrderFrequency()),
          score(input.weightAmountAnomaly()),
          score(input.weightAddressConcentration()),
          score(input.manualReviewThreshold()),
          score(input.holdShipmentThreshold()),
          input.frequencyWindowHours(),
          input.frequencyMaxOrders(),
          input.deviceLinkMaxLinks(),
          score(input.amountAnomalyRatio()),
          input.amountAnomalyMinOrders(),
          input.addressConcentrationWindowHours(),
          input.addressConcentrationMaxOrders(),
          input.signalMaxAgeHours(),
          input.missingSignalPolicy().name());
      return rule();
    }
    // ⚠️ version 必须递增：V150 的 guard_order_risk_rule_versioned 强制这一点，
    // 因为评估记录引用的是 (rule_id, rule_version)，同名规则会让「当时按哪一版权重算的」无法复算。
    sql.execute(
        "update order_risk_rule set rule_name=?,enabled=true,"
            + "weight_buyer_history=?,weight_device_ip_link=?,weight_order_frequency=?,"
            + "weight_amount_anomaly=?,weight_address_concentration=?,"
            + "manual_review_threshold=?,hold_shipment_threshold=?,"
            + "frequency_window_hours=?,frequency_max_orders=?,device_link_max_links=?,"
            + "amount_anomaly_ratio=?,amount_anomaly_min_orders=?,"
            + "address_concentration_window_hours=?,address_concentration_max_orders=?,"
            + "signal_max_age_hours=?,missing_signal_policy=?,version=version+1"
            + " where tenant_id = ?",
        input.ruleName().trim(),
        score(input.weightBuyerHistory()),
        score(input.weightDeviceIpLink()),
        score(input.weightOrderFrequency()),
        score(input.weightAmountAnomaly()),
        score(input.weightAddressConcentration()),
        score(input.manualReviewThreshold()),
        score(input.holdShipmentThreshold()),
        input.frequencyWindowHours(),
        input.frequencyMaxOrders(),
        input.deviceLinkMaxLinks(),
        score(input.amountAnomalyRatio()),
        input.amountAnomalyMinOrders(),
        input.addressConcentrationWindowHours(),
        input.addressConcentrationMaxOrders(),
        input.signalMaxAgeHours(),
        input.missingSignalPolicy().name(),
        tenant);
    return rule();
  }

  // ================================================================ 信号

  /**
   * 登记一条风险信号。
   *
   * <p>「采集时刻」必填且不允许留成登记时刻：一条三个月前的画像被当成今天的，
   * 正是误伤正常复购客户的成因。DB 侧还有两条硬闸：比率不得超过 1，
   * {@code MODULE_*} 来源必须带 source_reference。
   */
  @Transactional
  public RiskSignal saveSignal(RiskSignalInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    var unit = unitOf(input.signalCode());
    if (unit == MeasurementUnit.RATE && input.measuredValue().compareTo(BigDecimal.ONE) > 0) {
      throw bad(
          "「" + input.signalCode().label() + "」是比率，取值区间是 0..1；"
              + "收到 " + input.measuredValue() + "。百分比请传小数（30% 传 0.3）。");
    }
    if (unit == MeasurementUnit.COUNT && input.measuredValue().signum() <= 0) {
      throw bad(
          "「" + input.signalCode().label() + "」是计数，必须大于 0。"
              + "「关联数 = 0」的正确表达是**不登记这一行**，"
              + "登记一个 0 会让分数看起来像是认真量过的结果。");
    }
    String reference = nz(input.sourceReference()).trim();
    if (input.signalSource() != SignalSource.TENANT_MAINTAINED && reference.isEmpty()) {
      throw bad(
          "来源为 " + input.signalSource().label()
              + " 时必须给出 sourceReference 指明是哪份来源的哪一段："
              + "否则「这行是同步器写的」只是一句无法追溯的口头声明。");
    }
    UUID tenant = currentTenant();
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into order_risk_signal(id,tenant_id,signal_code,dimension,measurement_unit,"
            + "subject_kind,subject_key,measured_value,signal_source,source_reference,"
            + "captured_at,note,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,now())"
            + " on conflict (tenant_id,signal_code,subject_kind,subject_key,captured_at)"
            + " do update set measured_value = excluded.measured_value,"
            + " signal_source = excluded.signal_source,"
            + " source_reference = excluded.source_reference, note = excluded.note",
        id,
        tenant,
        input.signalCode().name(),
        dimensionOf(input.signalCode()).name(),
        unit.name(),
        input.subjectKind().name(),
        input.subjectKey().trim().toLowerCase(Locale.ROOT),
        qty(input.measuredValue()),
        input.signalSource().name(),
        reference,
        input.capturedAt(),
        nz(input.note()),
        OffsetDateTime.now());
    return latestSignal(input.signalCode(), input.subjectKind().name(), input.subjectKey().trim());
  }

  /** 信号列表（真分页 + 真搜索）。 */
  public RiskSignalPage pageSignals(SignalQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = " + TENANT_PREDICATE);
    if (notBlank(query.keyword())) {
      where.add("(subject_key ilike ? or source_reference ilike ? or note ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
      binds.add(k);
    }
    if (notBlank(query.signalCode())) {
      where.add("signal_code = ?");
      binds.add(query.signalCode().trim().toUpperCase(Locale.ROOT));
    }
    if (query.signalSource() != null) {
      where.add("signal_source = ?");
      binds.add(query.signalSource().name());
    }
    if (notBlank(query.subjectKey())) {
      where.add("subject_key = ?");
      binds.add(query.subjectKey().trim().toLowerCase(Locale.ROOT));
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) c from order_risk_signal s" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<RiskSignal> items =
        sql.fetch(
                "select s.* from order_risk_signal s" + sqlWhere
                    + " order by s.captured_at desc, s.id desc limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(OrderRiskService::signalRow);
    return new RiskSignalPage(items, total, pageNo, size, (int) Math.ceil(total / (double) size));
  }

  // ================================================================ 评估

  /**
   * 对一张订单跑一次风控评估。
   *
   * <p>写入顺序固定为「先插头 → 再写五行组件」：V150 的合计触发器挂在**第五个组件落下**时，
   * 头插入那一刻一个组件都还没有。
   *
   * <p>评估基准时刻恒等于订单的 {@code submitted_at}，不是 {@code now()} ——
   * 这样同一张单重复评估一万次结果完全一致，而「昨天放行、今天拒发」根本无从发生
   * （信号的新鲜度也是相对这个时刻判的，且**排除基准时刻之后采集的信号**：
   * 一条在订单提交之后才量到的画像，对这一单的决策没有贡献）。
   */
  @Transactional
  public AssessmentSaved assess(AssessmentInput input, UUID actorId) {
    if (input == null || input.orderId() == null) {
      throw bad("必须指明要评估的订单（orderId）");
    }
    RiskRule rule = requireRule();
    OrderFacts facts = orderFacts(input.orderId());
    UUID tenant = currentTenant();
    UUID assessmentId = UUID.randomUUID();

    // 头行先按「全缺」插入：CHECK (scored + missing = 5) 要求插入时就自洽，
    // 而真正的五个数字由第五个组件落下时的触发器算完再回填。
    sql.execute(
        "insert into order_risk_assessment(id,tenant_id,order_id,order_no,buyer_fingerprint,"
            + "order_currency,order_amount,order_submitted_at,rule_id,rule_version,rule_name,"
            + "evaluated_reference_at,risk_score,recommended_action,manual_review_required,"
            + "evaluation_status,scored_dimension_count,missing_dimension_count,created_by,created_at)"
            + " values(?,?,?,?,?,?,?,?::timestamptz,?,?,?,?::timestamptz,"
            + "null,'MANUAL_REVIEW',true,'INSUFFICIENT_SIGNALS',0,5,?,now())",
        assessmentId,
        tenant,
        facts.orderId(),
        facts.orderNo(),
        facts.buyerFingerprint(),
        facts.currency(),
        facts.amount(),
        facts.submittedAt(),
        rule.id(),
        rule.version(),
        rule.ruleName(),
        facts.submittedAt(),
        actorId);

    List<DraftComponent> drafts = new ArrayList<>();
    drafts.add(buyerHistory(rule, facts));
    drafts.add(deviceIpLink(rule, facts));
    drafts.add(orderFrequency(rule, facts));
    drafts.add(amountAnomaly(rule, facts));
    drafts.add(addressConcentration(rule, facts));

    for (DraftComponent draft : drafts) {
      sql.execute(
          "insert into order_risk_assessment_component(id,tenant_id,assessment_id,dimension,status,"
              + "weight,raw_value,normalized_score,contribution,signal_source,signal_captured_at,"
              + "explanation,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,now())",
          UUID.randomUUID(),
          tenant,
          assessmentId,
          draft.dimension().name(),
          draft.status().name(),
          score(draft.weight()),
          draft.rawValue() == null ? null : qty(draft.rawValue()),
          score(draft.normalized()),
          score(draft.contribution()),
          draft.source().name(),
          draft.capturedAt(),
          draft.explanation());
    }

    RiskDetail detail = assessment(assessmentId);
    return new AssessmentSaved(
        detail.assessment(),
        detail.components(),
        explain(
            "本次评估按规则「"
                + rule.ruleName()
                + "」第 "
                + rule.version()
                + " 版打分，基准时刻取订单提交时刻 "
                + facts.submittedAt()
                + "（不是当前时间）：同一张单重复评估结果恒定。"
                + (detail.assessment().riskScore() == null
                    ? "信号不足，未出分，只给「建议人工」—— 缺信号不出分比出一个残缺的分好，"
                        + "残缺的分会被下游当成「量过了，结论是低分」。"
                    : "总分 "
                        + detail.assessment().riskScore()
                        + " → 建议动作「"
                        + detail.assessment().recommendedAction().label()
                        + "」。")
                + "建议动作只是建议：真正的放行 / 拒发要等人工复核落一条结论。"));
  }

  /** 评估列表（真分页 + 真搜索），带不受分页影响的汇总。 */
  public RiskAssessmentPage pageAssessments(AssessmentQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("a.tenant_id = " + TENANT_PREDICATE);
    if (notBlank(query.keyword())) {
      where.add("(a.order_no ilike ? or a.rule_name ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    if (notBlank(query.recommendedAction())) {
      where.add("a.recommended_action = ?");
      binds.add(query.recommendedAction().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.evaluationStatus())) {
      where.add("a.evaluation_status = ?");
      binds.add(query.evaluationStatus().trim().toUpperCase(Locale.ROOT));
    }
    if (Boolean.TRUE.equals(query.pendingReviewOnly())) {
      where.add("a.manual_review_required and a.final_action is null");
    }
    if (notBlank(query.buyerFingerprint())) {
      where.add("a.buyer_fingerprint = ?");
      binds.add(query.buyerFingerprint().trim().toLowerCase(Locale.ROOT));
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) c from order_risk_assessment a" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    // ⚠️ 平手用 order_no 再用 id 打破，**不能**只用 id：uuid 是随机的，
    // 同一秒评估的两张单会让「谁在前面」每次刷新都不一样。
    List<RiskAssessment> items =
        sql.fetch(
                "select a.* from order_risk_assessment a" + sqlWhere
                    + " order by a.evaluated_reference_at desc, a.order_no, a.id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(OrderRiskService::assessmentRow);
    return new RiskAssessmentPage(items, total, pageNo, size, (int) Math.ceil(total / (double) size),
        summaryOf(sqlWhere, binds));
  }

  /** 评估详情：头 + 五行组件 + 人工复核留痕。跨租户在 RLS 下查不到 → 404。 */
  public RiskDetail assessment(UUID id) {
    Record head =
        sql.fetchOne(
            "select * from order_risk_assessment where tenant_id = " + TENANT_PREDICATE
                + " and id = ?",
            id);
    if (head == null) {
      throw notFound("风控评估不存在或不属于本租户（" + id + "）");
    }
    List<RiskComponent> components =
        sql.fetch(
                "select * from order_risk_assessment_component where tenant_id = " + TENANT_PREDICATE
                    + " and assessment_id = ?",
                id)
            .map(OrderRiskService::componentRow);
    RiskReview review = reviewOf(id);
    return new RiskDetail(assessmentRow(head), components, review);
  }

  // ================================================================ 人工复核

  /**
   * 提交人工复核 —— **本模块里唯一能产生「拒发」的地方**。
   *
   * <p>蓝图加粗句「风控分数不能单独作为拒绝依据，需人工复核，避免误伤正常复购客户」
   * 在这一行上落地：分数无论多高都产不出 final_action，必须先有人在这里落一笔。
   *
   * <p>{@code SHIP} / {@code REJECT} 会让 V150 的触发器回填评估的 final_action；
   * {@code DEFERRED} 与 {@code ESCALATED} **不改**结论 —— 「升级给合规」不等于「已拒发」。
   */
  @Transactional
  public ReviewSaved review(UUID assessmentId, RiskReviewInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    UUID tenant = currentTenant();
    Record head =
        sql.fetchOne(
            "select * from order_risk_assessment where tenant_id = " + TENANT_PREDICATE
                + " and id = ? for update",
            assessmentId);
    if (head == null) {
      throw notFound("风控评估不存在或不属于本租户（" + assessmentId + "）");
    }
    var assessment = assessmentRow(head);
    if (!assessment.manualReviewRequired()) {
      throw conflict(
          "这份评估的建议动作是「"
              + assessment.recommendedAction().label()
              + "」，不需要人工复核，给它补一条复核记录没有意义。");
    }
    var existing =
        sql.fetchOne(
            "select id from order_risk_review where tenant_id = " + TENANT_PREDICATE
                + " and assessment_id = ?",
            assessmentId);
    if (existing != null) {
      throw conflict(
          "这份评估已经复核过了。风控留痕不可覆写 —— 要改结论请重新评估一次"
              + "（那会带上新的信号与新的人工），而不是把上一次结论改掉。");
    }
    if (input.reasonCode() == ReviewReasonCode.FALSE_POSITIVE
        && input.decision() != ReviewDecision.SHIP) {
      throw bad(
          "原因码「误判，疑似正常复购客户」与结论「复核后拒发」自相矛盾。"
              + "确实是误判就应该发货；要拒发请用 CONFIRMED_RISK。");
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into order_risk_review(id,tenant_id,assessment_id,decision,reviewed_by,"
            + "reviewed_at,reason_code,note) values(?,?,?,?,?,?::timestamptz,?,?)",
        id,
        tenant,
        assessmentId,
        input.decision().name(),
        actorId,
        OffsetDateTime.now(),
        input.reasonCode().name(),
        nz(input.note()));
    RiskDetail detail = assessment(assessmentId);
    String tail =
        input.decision() == ReviewDecision.SHIP || input.decision() == ReviewDecision.REJECT
            ? "最终动作已回填为「" + detail.assessment().finalAction().label() + "」。"
            : "结论「"
                + input.decision().label()
                + "」**不改**最终动作：升级合规或在等材料都不是已经结案，"
                + "把它们当成「已拒发」是一起真实的客诉来源。";
    return new ReviewSaved(detail.assessment(), detail.review(),
        "复核已留痕（" + input.reasonCode().label() + "）。" + tail);
  }

  // ================================================================ 五个维度

  /**
   * 维度一：买家历史（退款率 / 纠纷率）—— 租户维护的信号。
   *
   * <p>取两者**较严重**的一项；两码都必须新鲜才算分。
   * 缺一码就判 MISSING_SIGNAL 并点名缺哪一码 —— 把「没量过纠纷率」当成 0 判成安全，
   * 正是本模块最不该犯的错。
   */
  private DraftComponent buyerHistory(RiskRule rule, OrderFacts facts) {
    BigDecimal weight = rule.weightBuyerHistory();
    var refund = freshSignal(rule, facts, SignalCode.REFUND_RATE);
    var dispute = freshSignal(rule, facts, SignalCode.DISPUTE_RATE);
    List<String> missing = new ArrayList<>();
    List<String> stale = new ArrayList<>();
    if (refund == null) {
      (hasSignalIgnoringFreshness(rule, facts, SignalCode.REFUND_RATE) ? stale : missing).add(SignalCode.REFUND_RATE.label());
    }
    if (dispute == null) {
      (hasSignalIgnoringFreshness(rule, facts, SignalCode.DISPUTE_RATE) ? stale : missing).add(SignalCode.DISPUTE_RATE.label());
    }
    if (!missing.isEmpty() || !stale.isEmpty()) {
      List<String> why = new ArrayList<>();
      if (!missing.isEmpty()) {
        why.add("未登记：" + String.join("、", missing));
      }
      if (!stale.isEmpty()) {
        why.add(
            "已登记但超出 "
                + rule.signalMaxAgeHours()
                + " 小时的新鲜度："
                + String.join("、", stale));
      }
      String text =
          "本维依赖 15 客户的行为画像，而卷 15 在集成头只有 15.4.1.1 交付（在 crm 独立库，"
              + "禁止跨库读表），因此由租户登记。" + String.join("；", why) + "。";
      // 「一条都没登记」与「登记了但过期」是两件事：前者是没数据，后者是数据太旧，
      // 处置动作不同（补登记 vs 重新采集）。全部算成 MISSING_SIGNAL 会把后者说成前者。
      return missing.isEmpty()
          ? stale(RiskDimension.BUYER_HISTORY, weight, SignalSource.MODULE_15_CUSTOMER_PROFILE,
              text)
          : missing(RiskDimension.BUYER_HISTORY, weight, SignalSource.MODULE_15_CUSTOMER_PROFILE,
              text);
    }
    BigDecimal worst = refund.value().max(dispute.value());
    return scored(
        RiskDimension.BUYER_HISTORY,
        weight,
        worst,
        scaled(worst.multiply(HUNDRED), SCORE_SCALE),
        SignalSource.MODULE_15_CUSTOMER_PROFILE,
        // 新鲜度相同用 captured_at 大的那条，保证确定性。
        refund.capturedAt().isAfter(dispute.capturedAt()) ? refund.capturedAt() : dispute.capturedAt(),
        "取退款率 "
            + refund.value()
            + " 与纠纷率 "
            + dispute.value()
            + " 中**较严重**的一项 "
            + worst
            + "（采集于 "
            + refund.capturedAt()
            + " / "
            + dispute.capturedAt()
            + "，来源 "
            + refund.source().name()
            + " / "
            + dispute.source().name()
            + "）。");
  }

  /**
   * 维度二：设备与 IP 关联 —— 租户维护的信号。
   *
   * <p>三个计数都取，归一化分取它们里最严重的那个打到 {@code device_link_max_links} 的比例。
   * 三码都必须新鲜：少一码就判不可用，而不是把没量过的共用 IP 数当成 0。
   */
  private DraftComponent deviceIpLink(RiskRule rule, OrderFacts facts) {
    BigDecimal weight = rule.weightDeviceIpLink();
    List<SignalCode> codes =
        List.of(
            SignalCode.DEVICE_IP_LINK_COUNT, SignalCode.SHARED_DEVICE_COUNT, SignalCode.SHARED_IP_COUNT);
    BigDecimal worst = null;
    OffsetDateTime newest = null;
    SignalSource source = null;
    List<String> used = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    List<String> stale = new ArrayList<>();
    for (SignalCode code : codes) {
      var hit = freshSignal(rule, facts, code);
      if (hit == null) {
        (hasSignalIgnoringFreshness(rule, facts, code) ? stale : missing).add(code.label());
        continue;
      }
      if (worst == null || hit.value().compareTo(worst) > 0) {
        worst = hit.value();
        newest = hit.capturedAt();
        source = hit.source();
      }
      used.add(code.label() + "=" + hit.value() + "（采集于 " + hit.capturedAt() + "）");
    }
    if (worst == null) {
      List<String> why = new ArrayList<>();
      if (!missing.isEmpty()) {
        why.add("未登记：" + String.join("、", missing));
      }
      if (!stale.isEmpty()) {
        why.add("已登记但超出 " + rule.signalMaxAgeHours() + " 小时的新鲜度：" + String.join("、", stale));
      }
      String text =
          "本维依赖 16 合规的关联风险信号，而卷 16 的 24 个 L4 模块在集成头**全部未交付**，"
              + "因此由租户登记。" + String.join("；", why) + "。";
      return missing.isEmpty()
          ? stale(RiskDimension.DEVICE_IP_LINK, weight, SignalSource.MODULE_16_RISK_LINKAGE, text)
          : missing(RiskDimension.DEVICE_IP_LINK, weight, SignalSource.MODULE_16_RISK_LINKAGE,
              text);
    }
    BigDecimal cap = BigDecimal.valueOf(rule.deviceLinkMaxLinks());
    BigDecimal ratio = worst.divide(cap, 8, RoundingMode.HALF_UP).min(BigDecimal.ONE);
    return scored(
        RiskDimension.DEVICE_IP_LINK,
        weight,
        worst,
        scaled(ratio.multiply(HUNDRED), SCORE_SCALE),
        source,
        newest,
        "取三个计数中最严重的一项 " + worst + "，打满档 " + cap + " → 归一化 "
            + scaled(ratio.multiply(HUNDRED), SCORE_SCALE)
            + "。明细："
            + String.join("，", used)
            + "。");
  }

  /**
   * 维度三：下单频次 —— **真 SQL**：同一买家在时间窗内的已提交订单笔数。
   *
   * <p>窗口是 {@code (基准时刻 − window, 基准时刻]}：把基准时刻本身算在内是对的（这张单
   * 本身就是一单），把基准时刻**之后**的单算进来是穿越（它们那时还不存在）。
   */
  private DraftComponent orderFrequency(RiskRule rule, OrderFacts facts) {
    BigDecimal weight = rule.weightOrderFrequency();
    if (facts.buyerFingerprint() == null) {
      return notApplicable(
          RiskDimension.ORDER_FREQUENCY,
          weight,
          "这张单既没有客户档案快照，也没有收货地址快照，买家无从判定。");
    }
    long count =
        sql.fetchOne(
                "select count(*) c from sales_order o"
                    + " join order_address_snapshot a on a.tenant_id=o.tenant_id"
                    + "   and a.order_id=o.id and a.address_role='SHIPPING'"
                    + " left join sales_order_party_snapshot p on p.tenant_id=o.tenant_id"
                    + "   and p.order_id=o.id"
                    + " where o.tenant_id = "
                    + TENANT_PREDICATE
                    + " and o.status='SUBMITTED'"
                    + " and o.submitted_at <= ?::timestamptz"
                    + " and o.submitted_at > ?::timestamptz"
                    + " and "
                    + BUYER_FINGERPRINT_SQL
                    + " = ?",
                facts.submittedAt(),
                facts.submittedAt().minusHours(rule.frequencyWindowHours()),
                facts.buyerFingerprint())
            .get("c", Long.class);
    BigDecimal cap = BigDecimal.valueOf(rule.frequencyMaxOrders());
    BigDecimal ratio =
        BigDecimal.valueOf(count).divide(cap, 8, RoundingMode.HALF_UP).min(BigDecimal.ONE);
    return scored(
        RiskDimension.ORDER_FREQUENCY,
        weight,
        BigDecimal.valueOf(count),
        scaled(ratio.multiply(HUNDRED), SCORE_SCALE),
        SignalSource.DERIVED_FROM_ORDERS,
        null,
        "买家 "
            + facts.buyerFingerprint()
            + " 在 "
            + (facts.submittedAt().minusHours(rule.frequencyWindowHours()))
            + " ~ "
            + facts.submittedAt()
            + " 的 "
            + rule.frequencyWindowHours()
            + " 小时窗内有 "
            + count
            + " 笔已提交订单（含本单），打满档 "
            + cap
            + " 笔。");
  }

  /**
   * 维度四：金额异常 —— **真 SQL**：本单金额与该买家**同币种**历史均单的比值。
   *
   * <p>同币种是硬口径：本服务没有任何汇率来源（10 财务未交付），
   * 拿 USD 与 EUR 比「金额异常」是纯噪声。
   */
  private DraftComponent amountAnomaly(RiskRule rule, OrderFacts facts) {
    BigDecimal weight = rule.weightAmountAnomaly();
    if (facts.buyerFingerprint() == null) {
      return notApplicable(
          RiskDimension.AMOUNT_ANOMALY,
          weight,
          "这张单既没有客户档案快照，也没有收货地址快照，买家无从判定。");
    }
    Record hist =
        sql.fetchOne(
            "select count(*) orders, coalesce(sum(o.grand_total_amount),0) total"
                + " from sales_order o"
                + " join order_address_snapshot a on a.tenant_id=o.tenant_id"
                + "   and a.order_id=o.id and a.address_role='SHIPPING'"
                + " left join sales_order_party_snapshot p on p.tenant_id=o.tenant_id"
                + "   and p.order_id=o.id"
                + " where o.tenant_id = "
                + TENANT_PREDICATE
                + " and o.status='SUBMITTED'"
                + " and o.currency = ?"
                + " and o.submitted_at < ?::timestamptz"
                + " and "
                + BUYER_FINGERPRINT_SQL
                + " = ?",
            facts.currency(),
            facts.submittedAt(),
            facts.buyerFingerprint());
    long historyOrders = hist.get("orders", Long.class);
    BigDecimal historyTotal = hist.get("total", BigDecimal.class);
    if (historyOrders < rule.amountAnomalyMinOrders()) {
      return new DraftComponent(
          RiskDimension.AMOUNT_ANOMALY,
          RiskComponentStatus.INSUFFICIENT_HISTORY,
          weight,
          null,
          BigDecimal.ZERO,
          SignalSource.DERIVED_FROM_ORDERS,
          null,
          "该买家在 "
              + facts.currency()
              + " 下只有 "
              + historyOrders
              + " 笔历史单，少于规则要求的 "
              + rule.amountAnomalyMinOrders()
              + " 笔：只有一两笔时「历史均单」就是它自己，比值恒为 1，判异常或正常都是噪声。"
              + "（跨币种不参与比较 —— 本服务没有汇率来源，10 财务未交付。）");
    }
    BigDecimal average =
        historyTotal.divide(BigDecimal.valueOf(historyOrders), 8, RoundingMode.HALF_UP);
    if (average.signum() == 0) {
      return new DraftComponent(
          RiskDimension.AMOUNT_ANOMALY,
          RiskComponentStatus.INSUFFICIENT_HISTORY,
          weight,
          null,
          BigDecimal.ZERO,
          SignalSource.DERIVED_FROM_ORDERS,
          null,
          "该买家 "
              + facts.currency()
              + " 历史均单为 0（多笔 0 元单），比值无定义。");
    }
    BigDecimal ratio = facts.amount().divide(average, 8, RoundingMode.HALF_UP);
    BigDecimal full = rule.amountAnomalyRatio();
    // 归一化：比值 1 → 0 分；比值 amount_anomaly_ratio（或更高）→ 100 分。
    BigDecimal span = full.subtract(BigDecimal.ONE);
    BigDecimal normalized =
        ratio
            .subtract(BigDecimal.ONE)
            .divide(span, 8, RoundingMode.HALF_UP)
            .max(BigDecimal.ZERO)
            .min(BigDecimal.ONE)
            .multiply(HUNDRED);
    return scored(
        RiskDimension.AMOUNT_ANOMALY,
        weight,
        ratio,
        scaled(normalized, SCORE_SCALE),
        SignalSource.DERIVED_FROM_ORDERS,
        null,
        "本单 "
            + money(facts.amount())
            + " "
            + facts.currency()
            + "，该买家同币种 "
            + historyOrders
            + " 笔历史均单 "
            + money(average)
            + "，比值 "
            + ratio
            + "（打满档 "
            + full
            + "）。");
  }

  /**
   * 维度五：地址集中度 —— **真 SQL**：同一收货地址指纹在时间窗内的订单笔数。
   *
   * <p>「同一地址」用收货地址**快照**而不是实时地址：V99 已把 SUBMITTED 后的快照冻住，
   * 判的是「这几单本来是不是寄到同一个地方」。
   */
  private DraftComponent addressConcentration(RiskRule rule, OrderFacts facts) {
    BigDecimal weight = rule.weightAddressConcentration();
    if (facts.addressFingerprint() == null) {
      return notApplicable(
          RiskDimension.ADDRESS_CONCENTRATION,
          weight,
          "这张单没有收货地址快照（SHIPPING），地址无从判定。");
    }
    long count =
        sql.fetchOne(
                "select count(*) c from sales_order o"
                    + " join order_address_snapshot a on a.tenant_id=o.tenant_id"
                    + "   and a.order_id=o.id and a.address_role='SHIPPING'"
                    + " where o.tenant_id = "
                    + TENANT_PREDICATE
                    + " and o.status='SUBMITTED'"
                    + " and o.submitted_at <= ?::timestamptz"
                    + " and o.submitted_at > ?::timestamptz"
                    + " and "
                    + ADDRESS_FINGERPRINT_SQL
                    + " = ?",
                facts.submittedAt(),
                facts.submittedAt().minusHours(rule.addressConcentrationWindowHours()),
                facts.addressFingerprint())
            .get("c", Long.class);
    BigDecimal cap = BigDecimal.valueOf(rule.addressConcentrationMaxOrders());
    BigDecimal ratio =
        BigDecimal.valueOf(count).divide(cap, 8, RoundingMode.HALF_UP).min(BigDecimal.ONE);
    return scored(
        RiskDimension.ADDRESS_CONCENTRATION,
        weight,
        BigDecimal.valueOf(count),
        scaled(ratio.multiply(HUNDRED), SCORE_SCALE),
        SignalSource.DERIVED_FROM_ORDERS,
        null,
        "收货地址 "
            + facts.addressFingerprint()
            + " 在 "
            + rule.addressConcentrationWindowHours()
            + " 小时窗内有 "
            + count
            + " 笔已提交订单（含本单），打满档 "
            + cap
            + " 笔。");
  }

  // ================================================================ 信号查找

  /**
   * 取「基准时刻之前、新鲜度之内」最新的一条信号。
   *
   * <p>排序 {@code captured_at desc, id desc}：同一毫秒采到两条时用 id 兜底，
   * 保证同样输入给同样结论。**排除基准时刻之后采集的信号** ——
   * 一条在订单提交之后才量到的画像，对这一单的决策没有贡献。
   */
  private SignalHit freshSignal(RiskRule rule, OrderFacts facts, SignalCode code) {
    if (facts.subjectKeys().isEmpty()) {
      return null;
    }
    List<Object> binds = new ArrayList<>();
    String subjectSql = subjectPredicate(binds, facts.subjectKeys());
    binds.add(0, facts.submittedAt().minusHours(rule.signalMaxAgeHours()));
    binds.add(0, facts.submittedAt());
    binds.add(0, code.name());
    Record row =
        sql.fetchOne(
            "select g.measured_value, g.signal_source, g.captured_at from order_risk_signal g"
                + " where g.tenant_id = "
                + TENANT_PREDICATE
                + " and g.signal_code = ?"
                + " and g.captured_at <= ?::timestamptz"
                + " and g.captured_at > ?::timestamptz"
                + subjectSql
                + " order by g.captured_at desc, g.id desc limit 1",
            binds.toArray());
    return row == null ? null : new SignalHit(row);
  }

  /** 「有登记但过期」的判定：与 {@link #freshSignal} 同一套键与窗口，只是不卡新鲜度。 */
  private boolean hasSignalIgnoringFreshness(RiskRule rule, OrderFacts facts, SignalCode code) {
    if (facts.subjectKeys().isEmpty()) {
      return false;
    }
    List<Object> binds = new ArrayList<>();
    String subjectSql = subjectPredicate(binds, facts.subjectKeys());
    binds.add(0, facts.submittedAt());
    binds.add(0, code.name());
    Long count =
        sql.fetchOne(
                "select count(*) c from order_risk_signal g"
                    + " where g.tenant_id = "
                    + TENANT_PREDICATE
                    + " and g.signal_code = ?"
                    + " and g.captured_at <= ?::timestamptz"
                    + subjectSql,
                binds.toArray())
            .get("c", Long.class);
    return count != null && count > 0;
  }

  /**
   * 按键的实际形状拼出 (CUSTOMER_PARTY 用 ::uuid / BUYER_FINGERPRINT 用文本) 的谓词。
   *
   * <p>刻意**动态拼**而不是「固定两个占位符各绑一个键」：只有一把钥匙时，
   * 固定写法会把一个占位符绑上不存在的键 —— 空键绑给 {@code ?::uuid} 会直接抛
   * 「invalid input syntax for type uuid」，而绑 0 又会静默查错人。
   *
   * <p>注意信号表存的是**裸键**（uuid 文本 / 32 位十六进制），
   * 而 {@link #BUYER_FINGERPRINT_SQL} 产出的是带 {@code 'P:' / 'A:'} 前缀的指纹
   * （前缀是给同一次评估里区分两套键用的），所以这里传进 subjectKeys 的必须是剥掉前缀的值。
   */
  private static String subjectPredicate(List<Object> binds, List<String> keys) {
    List<String> ors = new ArrayList<>();
    for (String key : keys) {
      if (UUID_SHAPE.matcher(key).matches()) {
        // ⚠️ 转换加在**列**一侧：`subject_key` 是 varchar，而 `varchar = uuid`
        // 在 PostgreSQL 里根本没有操作符（写 `subject_key = ?::uuid` 会报
        // "operator does not exist: character varying = uuid"）。
        // 反过来写 `g.subject_key::uuid = ?::uuid` 时 RLS 之外还能顺手拦掉
        // 形状不对的键，而不像全部走文本比较那样悄悄返回 0 行。
        ors.add("(g.subject_kind='CUSTOMER_PARTY' and g.subject_key::uuid=?::uuid)");
        binds.add(key);
      } else {
        ors.add("(g.subject_kind='BUYER_FINGERPRINT' and g.subject_key=?)");
        binds.add(key);
      }
    }
    return " and (" + String.join(" or ", ors) + ")";
  }

  /** 最近登记的一条信号（写入后回读）。 */
  private RiskSignal latestSignal(SignalCode code, String subjectKind, String subjectKey) {
    Record r =
        sql.fetchOne(
            "select s.* from order_risk_signal s where s.tenant_id = " + TENANT_PREDICATE
                + " and s.signal_code = ? and s.subject_kind = ? and s.subject_key = ?"
                + " order by s.captured_at desc, s.id desc limit 1",
            code.name(),
            subjectKind,
            subjectKey.toLowerCase(Locale.ROOT));
    return r == null ? null : signalRow(r);
  }

  // ================================================================ 订单事实

  /** 订单聚合特征：编号 / 币种 / 金额 / 提交时刻 / 两个指纹 / 信号可用键。 */
  private record OrderFacts(
      UUID orderId,
      String orderNo,
      String currency,
      BigDecimal amount,
      OffsetDateTime submittedAt,
      String buyerFingerprint,
      String addressFingerprint,
      List<String> subjectKeys) {}

  private OrderFacts orderFacts(UUID orderId) {
    Record r =
        sql.fetchOne(
            "select o.id, o.order_no, o.currency, o.grand_total_amount, o.submitted_at, o.status,"
                + " p.customer_party_id, "
                + BUYER_FINGERPRINT_SQL
                + " buyer_fingerprint, "
                + ADDRESS_FINGERPRINT_SQL
                + " address_fingerprint"
                + " from sales_order o"
                + " join order_address_snapshot a on a.tenant_id=o.tenant_id"
                + "   and a.order_id=o.id and a.address_role='SHIPPING'"
                + " left join sales_order_party_snapshot p on p.tenant_id=o.tenant_id"
                + "   and p.order_id=o.id"
                + " where o.tenant_id = "
                + TENANT_PREDICATE
                + " and o.id = ?",
            orderId);
    if (r == null) {
      throw notFound(
          "订单不存在、不属于本租户，或没有收货地址快照（"
              + orderId
              + "）。本模块的买家与地址指纹都来自收货地址快照（实查 V99：sales_order 没有买家身份列），"
              + "没有快照就判不了。");
    }
    if ("DRAFT".equals(r.get("status", String.class)) || r.get("submitted_at", OffsetDateTime.class) == null) {
      throw conflict(
          "订单 "
              + r.get("order_no", String.class)
              + " 还没提交（status=DRAFT）。风控评估针对的是已提交进审单池的订单："
                  + "草稿还没定稿，打出来的分没有意义。");
    }
    String buyer = r.get("buyer_fingerprint", String.class);
    String address = r.get("address_fingerprint", String.class);
    UUID partyId = r.get("customer_party_id", UUID.class);
    // 两把钥匙都放进 subjectKeys：租户登记时用哪一把都能查到，只登记了一把也照样查得到。
    // ⚠️ 买家指纹要**剥掉 'P:' / 'A:' 前缀**再当键用 —— 前缀只是同一次评估里区分
    // 两套键的标记，信号表里存的是裸值（uuid 文本 / 32 位十六进制）。
    List<String> keys = new ArrayList<>();
    if (partyId != null) {
      keys.add(partyId.toString());
    }
    if (buyer != null && buyer.startsWith("A:")) {
      keys.add(buyer.substring(2));
    }
    return new OrderFacts(
        r.get("id", UUID.class),
        r.get("order_no", String.class),
        r.get("currency", String.class),
        money(r.get("grand_total_amount", BigDecimal.class)),
        r.get("submitted_at", OffsetDateTime.class),
        buyer,
        address,
        keys);
  }

  // ================================================================ 打分草稿

  /** 一维的待落库结果。贡献由 {@link #scaled} 一次定到 4 位，DB 侧还会再校验一次。 */
  private record DraftComponent(
      RiskDimension dimension,
      RiskComponentStatus status,
      BigDecimal weight,
      BigDecimal rawValue,
      BigDecimal normalized,
      SignalSource source,
      OffsetDateTime capturedAt,
      String explanation) {

    BigDecimal contribution() {
      return status == RiskComponentStatus.SCORED
          ? weight.multiply(normalized).setScale(SCORE_SCALE, RoundingMode.HALF_UP)
          : BigDecimal.ZERO.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }
  }

  private record SignalHit(BigDecimal value, SignalSource source, OffsetDateTime capturedAt) {
    SignalHit(Record row) {
      this(
          row.get("measured_value", BigDecimal.class),
          SignalSource.valueOf(row.get("signal_source", String.class)),
          row.get("captured_at", OffsetDateTime.class));
    }
  }

  private static DraftComponent scored(
      RiskDimension dimension,
      BigDecimal weight,
      BigDecimal raw,
      BigDecimal normalized,
      SignalSource source,
      OffsetDateTime capturedAt,
      String explanation) {
    return new DraftComponent(
        dimension,
        RiskComponentStatus.SCORED,
        weight,
        raw,
        normalized,
        source,
        capturedAt,
        explanation);
  }

  private static DraftComponent missing(
      RiskDimension dimension, BigDecimal weight, SignalSource source, String explanation) {
    return new DraftComponent(
        dimension, RiskComponentStatus.MISSING_SIGNAL, weight, null, BigDecimal.ZERO, source, null,
        explanation);
  }

  private static DraftComponent stale(
      RiskDimension dimension, BigDecimal weight, SignalSource source, String explanation) {
    return new DraftComponent(
        dimension, RiskComponentStatus.STALE_SIGNAL, weight, null, BigDecimal.ZERO, source, null,
        explanation);
  }

  private static DraftComponent notApplicable(
      RiskDimension dimension, BigDecimal weight, String explanation) {
    return new DraftComponent(
        dimension, RiskComponentStatus.NOT_APPLICABLE, weight, null, BigDecimal.ZERO,
        SignalSource.DERIVED_FROM_ORDERS, null, explanation);
  }

  // ================================================================ 行映射

  private static RiskRule ruleRow(Record r) {
    return new RiskRule(
        r.get("id", UUID.class),
        r.get("rule_name", String.class),
        r.get("enabled", Boolean.class),
        score(r.get("weight_buyer_history", BigDecimal.class)),
        score(r.get("weight_device_ip_link", BigDecimal.class)),
        score(r.get("weight_order_frequency", BigDecimal.class)),
        score(r.get("weight_amount_anomaly", BigDecimal.class)),
        score(r.get("weight_address_concentration", BigDecimal.class)),
        score(r.get("manual_review_threshold", BigDecimal.class)),
        score(r.get("hold_shipment_threshold", BigDecimal.class)),
        r.get("frequency_window_hours", Integer.class),
        r.get("frequency_max_orders", Integer.class),
        r.get("device_link_max_links", Integer.class),
        score(r.get("amount_anomaly_ratio", BigDecimal.class)),
        r.get("amount_anomaly_min_orders", Integer.class),
        r.get("address_concentration_window_hours", Integer.class),
        r.get("address_concentration_max_orders", Integer.class),
        r.get("signal_max_age_hours", Integer.class),
        MissingSignalPolicy.valueOf(r.get("missing_signal_policy", String.class)),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static RiskSignal signalRow(Record r) {
    return new RiskSignal(
        r.get("id", UUID.class),
        SignalCode.valueOf(r.get("signal_code", String.class)),
        RiskDimension.valueOf(r.get("dimension", String.class)),
        MeasurementUnit.valueOf(r.get("measurement_unit", String.class)),
        SubjectKind.valueOf(r.get("subject_kind", String.class)),
        r.get("subject_key", String.class),
        qty(r.get("measured_value", BigDecimal.class)),
        SignalSource.valueOf(r.get("signal_source", String.class)),
        r.get("source_reference", String.class),
        r.get("captured_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static RiskComponent componentRow(Record r) {
    return new RiskComponent(
        r.get("id", UUID.class),
        RiskDimension.valueOf(r.get("dimension", String.class)),
        RiskComponentStatus.valueOf(r.get("status", String.class)),
        score(r.get("weight", BigDecimal.class)),
        qty(r.get("raw_value", BigDecimal.class)),
        score(r.get("normalized_score", BigDecimal.class)),
        score(r.get("contribution", BigDecimal.class)),
        SignalSource.valueOf(r.get("signal_source", String.class)),
        r.get("signal_captured_at", OffsetDateTime.class),
        r.get("explanation", String.class));
  }

  private static RiskAssessment assessmentRow(Record r) {
    var finalAction = r.get("final_action", String.class);
    return new RiskAssessment(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        r.get("buyer_fingerprint", String.class),
        r.get("order_currency", String.class),
        money(r.get("order_amount", BigDecimal.class)),
        r.get("order_submitted_at", OffsetDateTime.class),
        r.get("rule_id", UUID.class),
        r.get("rule_name", String.class),
        r.get("rule_version", Long.class),
        r.get("evaluated_reference_at", OffsetDateTime.class),
        score(r.get("risk_score", BigDecimal.class)),
        RecommendedAction.valueOf(r.get("recommended_action", String.class)),
        r.get("manual_review_required", Boolean.class),
        EvaluationStatus.valueOf(r.get("evaluation_status", String.class)),
        r.get("scored_dimension_count", Integer.class),
        r.get("missing_dimension_count", Integer.class),
        finalAction == null ? null : FinalAction.valueOf(finalAction),
        r.get("reviewed_at", OffsetDateTime.class),
        r.get("reviewed_by", UUID.class),
        r.get("totals_computed_at", OffsetDateTime.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private RiskReview reviewOf(UUID assessmentId) {
    Record r =
        sql.fetchOne(
            "select * from order_risk_review where tenant_id = " + TENANT_PREDICATE
                + " and assessment_id = ?",
            assessmentId);
    return r == null
        ? null
        : new RiskReview(
            r.get("id", UUID.class),
            r.get("assessment_id", UUID.class),
            ReviewDecision.valueOf(r.get("decision", String.class)),
            r.get("reviewed_by", UUID.class),
            r.get("reviewed_at", OffsetDateTime.class),
            ReviewReasonCode.valueOf(r.get("reason_code", String.class)),
            r.get("note", String.class));
  }

  // ================================================================ 汇总

  /** 全量汇总（不受筛选与分页影响）。 */
  public Summary overview() {
    Record r =
        sql.fetchOne(
            "select count(*) total,"
                + " count(*) filter (where evaluation_status='SCORED') scored,"
                + " count(*) filter (where evaluation_status='INSUFFICIENT_SIGNALS') insufficient,"
                + " count(*) filter (where manual_review_required and final_action is null) pending,"
                + " count(*) filter (where recommended_action='RELEASE') released,"
                + " count(*) filter (where recommended_action='MANUAL_REVIEW') manual,"
                + " count(*) filter (where recommended_action='HOLD_SHIPMENT') hold,"
                + " count(*) filter (where missing_dimension_count>0) incomplete"
                + " from order_risk_assessment where tenant_id = "
                + TENANT_PREDICATE);
    return new Summary(
        r.get("total", Long.class),
        r.get("scored", Long.class),
        r.get("insufficient", Long.class),
        r.get("pending", Long.class),
        r.get("released", Long.class),
        r.get("manual", Long.class),
        r.get("hold", Long.class),
        r.get("incomplete", Long.class));
  }

  /** 按当前筛选条件的汇总。 */
  private Summary summaryOf(String sqlWhere, List<Object> binds) {
    Record r =
        sql.fetchOne(
            "select count(*) total,"
                + " count(*) filter (where a.evaluation_status='SCORED') scored,"
                + " count(*) filter (where a.evaluation_status='INSUFFICIENT_SIGNALS') insufficient,"
                + " count(*) filter (where a.manual_review_required and a.final_action is null) pending,"
                + " count(*) filter (where a.recommended_action='RELEASE') released,"
                + " count(*) filter (where a.recommended_action='MANUAL_REVIEW') manual,"
                + " count(*) filter (where a.recommended_action='HOLD_SHIPMENT') hold,"
                + " count(*) filter (where a.missing_dimension_count>0) incomplete"
                + " from order_risk_assessment a"
                + sqlWhere,
            binds.toArray());
    return new Summary(
        r.get("total", Long.class),
        r.get("scored", Long.class),
        r.get("insufficient", Long.class),
        r.get("pending", Long.class),
        r.get("released", Long.class),
        r.get("manual", Long.class),
        r.get("hold", Long.class),
        r.get("incomplete", Long.class));
  }

  // ================================================================ 枚举映射与工具

  /** 信号码 → 维度（DB 侧有同样的 CHECK 钉死，这里只是传输校验）。 */
  private static RiskDimension dimensionOf(SignalCode code) {
    return switch (code) {
      case REFUND_RATE, DISPUTE_RATE -> RiskDimension.BUYER_HISTORY;
      case DEVICE_IP_LINK_COUNT, SHARED_DEVICE_COUNT, SHARED_IP_COUNT -> RiskDimension.DEVICE_IP_LINK;
    };
  }

  private static MeasurementUnit unitOf(SignalCode code) {
    return switch (code) {
      case REFUND_RATE, DISPUTE_RATE -> MeasurementUnit.RATE;
      default -> MeasurementUnit.COUNT;
    };
  }

  /** 落库结果里那句人话说明的构造（刻意与 Summary 同名但不同义，避免混淆）。 */
  private static String explain(String text) {
    return text;
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

  /** 数量/比率刻度：6 位，HALF_UP（与 DB 的 numeric(19,6) 对齐）。 */
  private static BigDecimal qty(BigDecimal value) {
    return value == null ? null : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  /** 金额刻度：4 位，HALF_UP（与 DB 的 numeric(19,4) 对齐）。 */
  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 分数/权重刻度：4 位，HALF_UP（与 DB 的 numeric(9,4) 对齐）。 */
  private static BigDecimal score(BigDecimal value) {
    return value == null ? null : value.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal scaled(BigDecimal value, int scale) {
    return value.setScale(scale, RoundingMode.HALF_UP);
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
