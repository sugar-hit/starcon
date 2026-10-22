package com.starcon.channel;

import static com.starcon.channel.BuyerRiskScoreModels.*;
import static com.starcon.channel.BuyerRiskScoreRules.*;

import com.starcon.channel.BuyerRiskScoreRules.Dimension;
import com.starcon.channel.BuyerRiskScoreRules.Feature;
import com.starcon.channel.BuyerRiskScoreRules.Outcome;
import com.starcon.channel.BuyerRiskScoreSourcePort.SourceBuyerProfile;
import com.starcon.channel.BuyerRiskScoreSourcePort.SourceBuyerProfiles;
import com.starcon.channel.BuyerRiskScoreSourcePort.SourceFraudCaseRefs;
import com.starcon.channel.BuyerRiskScoreSourcePort.SourceOrder;
import com.starcon.channel.BuyerRiskScoreSourcePort.SourceOrderFacts;
import com.starcon.channel.BuyerRiskScoreSourcePort.SourceOrders;
import com.starcon.channel.BuyerRiskScoreSourcePort.SourceRefundRefs;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.5.1.2 风险评分。
 *
 * <p>蓝图：「计算买家风险分：历史退款率、纠纷率、欺诈特征、地址模式、下单行为、关联账号，
 * 输出评分与建议动作。输入：买家行为数据；风险特征规则；权重配置。输出：风险评分 + 特征解释。
 * 关系：上游 = 15 客户、11 售后、9 订单；下游 = 16.5.1.1、9.2.2.2。
 * <b>评分要能解释</b>（为什么这个买家 80 分），黑盒分数客服无法向平台申诉。」
 *
 * <p>🔴🔴 本类里有<b>七条结构上的不变量</b>，每一条都对应蓝图或 AGENTS.md 的一条硬要求：
 *
 * <ol>
 *   <li><b>主体键是 {@code buyer_key}（收件人三件套的 md5），不是 {@code customer_party_id}</b>：
 *       平台 B2C 买家绝大多数没有 party_id，用 party_id 会把绝大多数买家漏掉。
 *       口径与 trade 的 {@code order_review_buyer_profile.buyer_key} 逐字同一公式。</li>
 *   <li><b>量不到 ≠ 0</b>：读不到的特征<b>不进分母</b>、<b>不算通过</b>、<b>不给 0 分</b>。
 *       「没数据」说成「这买家很干净」是这类系统最致命的一种撒谎。</li>
 *   <li><b>纠纷率本轮量不到</b>：trade 的 {@code platform_dispute} 没有 order_id、
 *       也没有任何买家标识 ⇒ 该维度 available=false + 归属 11.4.1.1，<b>不参与加权</b>。
 *       <b>不用 platform_order_no 去字符串匹配凑数</b>，也<b>不用 0 冒充「纠纷率 0」</b>。</li>
 *   <li><b>退款率走「订单 → 买家」两级关联</b>：先按 buyer_key 找到订单集合，
 *       再按 order_id 统计退款（退款表上只有 order_id，没有买家列）。</li>
 *   <li><b>权重是租户可配的，合计钉死 1.0000</b>（DB 层 CHECK + 服务层再查一次）。
 *       缺信号口径（SCORE_KNOWN_ONLY / BLOCK_EVALUATION）<b>必须由租户显式选</b>。</li>
 *   <li><b>判定凭据 append-only</b>：评分与特征明细一次写入即不可改；
 *       权重改了只影响<b>新</b>的评分，历史评分原样留着。</li>
 *   <li><b>只出建议，不改订单</b>：建议动作 RELEASE / MANUAL_REVIEW / HOLD，
 *       <b>不写黑名单</b>（16.5.1.1 未交付）、<b>不编关联账号图谱</b>（16.5.2.2 未交付）。</li>
 * </ol>
 */
@Service
public class BuyerRiskScoreService {

  /** 主体键口径（能力声明里原样报出）。 */
  private static final String SUBJECT_SEMANTICS =
      "🔴 评分主体键是 **buyer_key**（收货地址快照三件套的 md5，32 位十六进制），"
          + "与 trade 的 order_review_buyer_profile.buyer_key 逐字同一公式"
          + "（md5(utf8(姓名 ‖ U+001F ‖ 电话 ‖ U+001F ‖ 邮箱))，分隔符用不可见字符而不是 '|'，"
          + "理由见 V144 文件头：'|' 会让两组不同字段拼出同一个串，那是一次跨买家的错误合并）。";

  /** 为什么选 buyer_key 而不是 customer_party_id。 */
  private static final String IDENTITY_CHOICE_NOTE =
      "🔴 本仓有**两套互不兼容的买家标识**，本模块明确选 buyer_key（收件人三件套的 md5）："
          + "customer_party_id 是 catalog 的 party.id（**B2B 主体**口径），平台 B2C 买家绝大多数**没有** party_id"
          + "（见 V149__trade_order_review_rules.sql 的说明），用它会把绝大多数买家漏掉。"
          + "另：crm 服务**没有买家档案**（它自己的迁移注释写明「customer_ref 只是一条逻辑引用」），"
          + "所以本模块**不把 crm 的 customer_ref 当买家主键去 join**。";

  /** 分数口径。 */
  private static final String SCORE_SEMANTICS =
      "🔴 分数区间 0..100（numeric(9,4)，BigDecimal，禁浮点）。"
          + "总分 = Σ(标准化值 × 权重) ÷ Σ(已知维度权重) × 100，"
          + "**逐条贡献分四舍五入到 4 位小数后求和，这个和就是总分** —— 界面上「各特征贡献分加起来」"
          + "必须正好等于「总分」，否则客服解释不清。";

  /** 缺特征口径。 */
  private static final String MISSING_SIGNAL_SEMANTICS =
      "🔴 「量不到」与「量为 0」是**两件事**：量不到的特征**不进分母、不算通过、不给 0 分**，"
          + "它的标准化值与贡献分都是 null，界面上显示「未知」并写清量不到的原因与归属模块。"
          + "缺信号口径由租户显式选：SCORE_KNOWN_ONLY（按已有维度归一化）或 "
          + "BLOCK_EVALUATION（任一维度缺信号就不出分，只给「建议人工」）。";

  /** 可解释性口径。 */
  private static final String EXPLAIN_SEMANTICS =
      "🔴 评分**必须能解释**：每一次评分都落逐特征的贡献明细（特征码 / 原始值 / 标准化值 / 权重 / "
          + "贡献分 / 人话理由 / 量不到时的归属模块）。「为什么这个买家 80 分」必须能逐条答出来 —— "
          + "黑盒分数客服无法向平台申诉。";

  /** 回写口径。 */
  private static final String WRITEBACK_NOTE =
      "🔴 退款率 / 纠纷率在能读到时回写 9.2.2.2 的风控信号（POST /trade/orders/risk-validation/signals，"
          + "signalSource 固定 TENANT_MAINTAINED，subjectKind=BUYER_FINGERPRINT）。"
          + "**回写失败不阻塞评分**：那个端点是用户权限端点（要 trade.orders.update、转发调用方自己的 bearer），"
          + "不是签名的服务间内部 API，调用方是否真有那个权限未经运行时验证 ⇒ 失败时记「未回写」并写明原因。"
          + "**买家综合分留在本服务**（trade 的 signal_code 枚举里没有「买家综合分」这个码，本模块不改 trade 的枚举）。";

  /** 下游口径。 */
  private static final String DOWNSTREAM_NOTE =
      "🔴 下游两个模块当前**都未交付**：16.5.1.1 黑名单（未交付 ⇒ 本模块**不写黑名单、不声称已拉黑**，"
          + "只读 trade 侧的画像标记）、9.2.2.2 风控校验（已交付，但只回写 REFUND_RATE / DISPUTE_RATE 两个信号码，"
          + "综合分它接不了）。本模块**只出建议**，不直接改订单状态 —— 改订单是 9.2 的事。";

  /** 边界口径。 */
  private static final String BOUNDARY_NOTE =
      "🔴 两条边界写死在能力声明里：**不编关联账号图谱**（16.5.2.2 关联风险未交付 ⇒ "
          + "「关联账号」维度 available=false，且**刻意没有权重列** —— 一个永远量不到的维度占着权重会稀释分数）、"
          + "**不用 platform_order_no 字符串匹配凑纠纷率**（平台纠纷没有订单 / 买家 join 键，"
          + "凑出来的纠纷率看似有数、实则不可信）。";

  private final DSLContext sql;
  private final BuyerRiskScoreSourcePort source;

  public BuyerRiskScoreService(DSLContext sql, BuyerRiskScoreSourcePort source) {
    this.sql = sql;
    this.source = source;
  }

  // ================================================================ 能力声明

  /** 能力声明：逐维 available + ownerModule + 逐个上游读面的可用性。 */
  public BuyerRiskCapabilities capabilities() {
    List<BuyerRiskDimensionCapability> dimensions =
        List.of(
            new BuyerRiskDimensionCapability(
                Dimension.REFUND_HISTORY.name(),
                Dimension.REFUND_HISTORY.label(),
                Dimension.REFUND_HISTORY.featureCode(),
                true,
                "9 订单（sales_order + 收货地址快照）+ 11 售后（refund_application / refund_execution）",
                "",
                "能算：先按 buyer_key 找到订单集合，再按 order_id 统计发起过退款的订单。"
                    + "前提是订单列表与退款列表都读得到 —— 任一读不到，该维度量不到（不是 0）。"),
            new BuyerRiskDimensionCapability(
                Dimension.DISPUTE_HISTORY.name(),
                Dimension.DISPUTE_HISTORY.label(),
                Dimension.DISPUTE_HISTORY.featureCode(),
                false,
                "11.4.1.1 平台纠纷（trade 的 platform_dispute）",
                "11.4.1.1",
                "🔴 **本轮量不到**：platform_dispute 既没有 order_id、也没有任何买家标识，"
                    + "只有一个自由文本 platform_order_no。用字符串匹配凑纠纷率会算出看似有数、"
                    + "实则不可信的分数 ⇒ 本维度不参与加权，结论里显式写「纠纷率未计入」。"),
            new BuyerRiskDimensionCapability(
                Dimension.FRAUD_PROFILE.name(),
                Dimension.FRAUD_PROFILE.label(),
                Dimension.FRAUD_PROFILE.featureCode(),
                true,
                "9.2.1.2 买家画像（order_review_buyer_profile）+ 11 售后 reason_code=FRAUD_RETURN",
                "",
                "能算：已登记画像的买家按「是否拉黑 / 等级是否为 RISK」取值，"
                    + "并叠加「欺诈退货」售后单占比。**没登记画像的买家该维度量不到** —— "
                    + "没有画像不等于干净买家。"),
            new BuyerRiskDimensionCapability(
                Dimension.ADDRESS_PATTERN.name(),
                Dimension.ADDRESS_PATTERN.label(),
                Dimension.ADDRESS_PATTERN.featureCode(),
                true,
                "9 订单收货地址快照（order_address_snapshot，随订单冻结）",
                "",
                "能算：按该买家的订单集合实算地址分散度与地址字段缺失率。"),
            new BuyerRiskDimensionCapability(
                Dimension.ORDER_BEHAVIOR.name(),
                Dimension.ORDER_BEHAVIOR.label(),
                Dimension.ORDER_BEHAVIOR.featureCode(),
                true,
                "9 订单下单时间（sales_order.created_at）",
                "",
                "能算：按统计窗口内的订单笔数、短时多单（默认 24 小时内几笔）与下单频率折算。"),
            new BuyerRiskDimensionCapability(
                Dimension.RELATED_ACCOUNTS.name(),
                Dimension.RELATED_ACCOUNTS.label(),
                Dimension.RELATED_ACCOUNTS.featureCode(),
                false,
                "16.5.2.2 关联风险（未交付）",
                "16.5.2.2",
                "🔴 **未交付**：本模块**不编一张「关联账号」表**（那是 16.5.2.2 的职责）。"
                    + "该维度**刻意没有权重列** —— 一个永远量不到的维度占着权重会稀释分数。"));

    List<BuyerRiskSourceState> sources =
        List.of(
            new BuyerRiskSourceState(
                "BUYER_PROFILES",
                "9.2.1.2 买家画像（trade）",
                true,
                "租户自维护的买家名册，带 buyer_key —— 本模块的主体来源。"),
            new BuyerRiskSourceState(
                "SALES_ORDERS",
                "9 订单列表 + 收货地址快照（trade）",
                true,
                "按统计窗口取订单，逐单用收货地址快照实算 buyer_key。"
                    + "⚠️ 订单集合受调用方数据范围影响（SELF 时只看到本人经手的订单）⇒ "
                    + "相关比率可能偏低，这是上游授权模型的固有属性。"),
            new BuyerRiskSourceState(
                "REFUNDS",
                "11 售后退款（trade）",
                true,
                "退款表只有 order_id、没有买家列 ⇒ 退款率必须走「订单 → 买家」两级关联。"),
            new BuyerRiskSourceState(
                "AFTERSALES_CASES",
                "11 售后单（trade）",
                true,
                "只有 reason_code=FRAUD_RETURN 那一类用于欺诈特征；case_type 的受控值域里**没有「纠纷」**。"),
            new BuyerRiskSourceState(
                "RISK_SIGNALS",
                "9.2.2.2 风控信号（trade）",
                true,
                "回写 REFUND_RATE / DISPUTE_RATE 两个信号码；回写失败不阻塞评分。"));

    return new BuyerRiskCapabilities(
        dimensions,
        sources,
        SUBJECT_SEMANTICS,
        IDENTITY_CHOICE_NOTE,
        SCORE_SEMANTICS,
        MISSING_SIGNAL_SEMANTICS,
        EXPLAIN_SEMANTICS,
        WRITEBACK_NOTE,
        DOWNSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  // ================================================================ 规则

  /**
   * 读取规则。
   *
   * <p>🔴 <b>刻意不像 trade 那样「没登记过就 404」</b>，而是返回 {@code configured=false}：
   * 本模块的界面上必须能同时呈现「还没登记规则」与「登记规则」两种状态，
   * 404 会让前端把「没登记」误读成「读失败」。返回体里<b>不预填任何默认权重</b> ——
   * 凭空给一份默认权重，运营会以为那是系统口径。
   */
  public BuyerRiskRuleView rule() {
    RuleRow row = loadRule();
    if (row == null) {
      return new BuyerRiskRuleView(
          null, "", null, null, null, null, null, null, null, null, null, null, null, null, null,
          null, null, null, null, 0L, false, null, null);
    }
    return row.toView();
  }

  /** 登记 / 修改规则（upsert，一租户一条）。权重合计必须恰好 1.0000。 */
  public BuyerRiskRuleView saveRule(BuyerRiskRuleInput input) {
    UUID tenant = currentTenant();
    BigDecimal sum =
        input.weightRefundHistory()
            .add(input.weightDisputeHistory())
            .add(input.weightFraudProfile())
            .add(input.weightAddressPattern())
            .add(input.weightOrderBehavior());
    if (sum.compareTo(BigDecimal.ONE) != 0) {
      throw unprocessable(
          "五个维度的权重合计必须恰好是 1.0000（现在是 "
              + sum.setScale(4, RoundingMode.HALF_UP).toPlainString()
              + "）—— 权重不配平的评分会把分数算歪。");
    }
    if (input.holdThreshold().compareTo(input.manualReviewThreshold()) < 0) {
      throw unprocessable("建议暂缓的阈值不能低于建议人工复核的阈值。");
    }

    RuleRow existing = loadRule();
    if (existing != null
        && input.version() != null
        && input.version() != existing.version()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "规则已经被别人改过了（当前版本 " + existing.version() + "），请刷新后重试");
    }

    UUID id = existing == null ? Ids.next() : existing.id();
    OffsetDateTime now = dbNow();
    sql.execute(
        """
        insert into buyer_risk_rule
          (id, tenant_id, rule_name, weight_refund_history, weight_dispute_history,
           weight_fraud_profile, weight_address_pattern, weight_order_behavior,
           missing_signal_policy, manual_review_threshold, hold_threshold,
           refund_rate_full_risk, fraud_blacklisted_score, fraud_risk_level_score,
           fraud_case_full_risk, address_single_ratio_threshold,
           order_burst_window_hours, order_burst_threshold, order_frequency_full_risk,
           order_lookback_days, version, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
        on conflict (tenant_id) do update set
          rule_name = excluded.rule_name,
          weight_refund_history = excluded.weight_refund_history,
          weight_dispute_history = excluded.weight_dispute_history,
          weight_fraud_profile = excluded.weight_fraud_profile,
          weight_address_pattern = excluded.weight_address_pattern,
          weight_order_behavior = excluded.weight_order_behavior,
          missing_signal_policy = excluded.missing_signal_policy,
          manual_review_threshold = excluded.manual_review_threshold,
          hold_threshold = excluded.hold_threshold,
          refund_rate_full_risk = excluded.refund_rate_full_risk,
          fraud_blacklisted_score = excluded.fraud_blacklisted_score,
          fraud_risk_level_score = excluded.fraud_risk_level_score,
          fraud_case_full_risk = excluded.fraud_case_full_risk,
          address_single_ratio_threshold = excluded.address_single_ratio_threshold,
          order_burst_window_hours = excluded.order_burst_window_hours,
          order_burst_threshold = excluded.order_burst_threshold,
          order_frequency_full_risk = excluded.order_frequency_full_risk,
          order_lookback_days = excluded.order_lookback_days,
          version = buyer_risk_rule.version + 1,
          updated_at = ?::timestamptz
        """,
        id,
        tenant,
        input.ruleName().trim(),
        input.weightRefundHistory(),
        input.weightDisputeHistory(),
        input.weightFraudProfile(),
        input.weightAddressPattern(),
        input.weightOrderBehavior(),
        input.missingSignalPolicy(),
        input.manualReviewThreshold(),
        input.holdThreshold(),
        input.refundRateFullRisk(),
        input.fraudBlacklistedScore(),
        input.fraudRiskLevelScore(),
        input.fraudCaseFullRisk(),
        input.addressSingleRatioThreshold(),
        input.orderBurstWindowHours(),
        input.orderBurstThreshold(),
        input.orderFrequencyFullRisk(),
        input.orderLookbackDays(),
        existing == null ? 1L : existing.version() + 1,
        now,
        now,
        now);
    return rule();
  }

  // ================================================================ 评估

  /**
   * 跑一次评分。
   *
   * @param buyerKey 只评一个买家时填；留空 ⇒ 评「画像买家 ∪ 订单里发现的买家」
   */
  public BuyerRiskEvaluateResult evaluate(String bearer, BuyerRiskEvaluateInput input) {
    UUID tenant = currentTenant();
    RuleRow rule = loadRule();
    if (rule == null) {
      throw unprocessable(
          "还没有登记评分规则 —— 权重与缺信号口径必须由租户显式选，系统不给默认值。");
    }

    OffsetDateTime reference = dbNow();
    OffsetDateTime since = reference.minusDays(rule.orderLookbackDays());

    SourceBuyerProfiles profiles = source.buyerProfiles(bearer);
    SourceOrders orders = source.orders(bearer, since);
    SourceRefundRefs refunds = source.refundedOrderIds(bearer);
    SourceFraudCaseRefs cases = source.fraudReturnOrderIds(bearer);

    List<BuyerRiskSourceState> sourceStates =
        List.of(
            new BuyerRiskSourceState(
                "BUYER_PROFILES", "9.2.1.2 买家画像（trade）", profiles.available(), profiles.note()),
            new BuyerRiskSourceState(
                "SALES_ORDERS", "9 订单列表（trade）", orders.available(), orders.note()),
            new BuyerRiskSourceState(
                "REFUNDS", "11 售后退款（trade）", refunds.available(), refunds.note()),
            new BuyerRiskSourceState(
                "AFTERSALES_CASES", "11 售后单（trade）", cases.available(), cases.note()));

    // 🔴 逐单实算买家身份键（收货地址快照三件套的 md5，与 trade 同一公式）。
    Map<String, List<BuyerOrder>> byBuyer = new LinkedHashMap<>();
    int unresolved = 0;
    List<String> unresolvedNotes = new ArrayList<>();
    if (orders.available()) {
      for (SourceOrder order : orders.orders()) {
        UUID orderId;
        try {
          orderId = UUID.fromString(order.orderId());
        } catch (RuntimeException e) {
          unresolved++;
          continue;
        }
        SourceOrderFacts facts = source.orderFacts(bearer, orderId);
        if (!facts.available()) {
          unresolved++;
          if (unresolvedNotes.size() < 3) {
            unresolvedNotes.add(
                "订单 " + order.orderNo() + "：" + facts.note());
          }
          continue;
        }
        byBuyer
            .computeIfAbsent(facts.buyerKey(), key -> new ArrayList<>())
            .add(new BuyerOrder(order, facts));
      }
    }

    // 🔴 目标买家集合 = 画像买家 ∪ 订单里发现的买家。
    Map<String, SourceBuyerProfile> roster = new LinkedHashMap<>();
    if (profiles.available()) {
      for (SourceBuyerProfile profile : profiles.profiles()) {
        roster.put(profile.buyerKey(), profile);
      }
    }
    Set<String> targets = new LinkedHashSet<>(roster.keySet());
    targets.addAll(byBuyer.keySet());

    String requested = input == null ? "" : nz(input.buyerKey()).trim().toLowerCase(Locale.ROOT);
    if (!requested.isEmpty()) {
      if (!requested.matches("^[0-9a-f]{32}$")) {
        throw unprocessable("买家身份键必须是 32 位十六进制（buyer_key）。");
      }
      if (!targets.contains(requested)) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "这个买家既没有登记画像、也没有出现在统计窗口内的订单里 —— 量不到任何维度，不产出评分。");
      }
      targets = new LinkedHashSet<>(List.of(requested));
    }

    if (targets.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "上游一个买家都读不到："
              + sourceStates.stream()
                  .filter(state -> !state.available())
                  .map(state -> state.sourceLabel() + "（" + state.note() + "）")
                  .reduce((a, b) -> a + "；" + b)
                  .orElse("（读面都成功，但既没有画像也没有订单）")
              + " —— 没有依据可算分，不产出评分。");
    }

    List<BuyerRiskScoreView> scores = new ArrayList<>();
    int scoredCount = 0;
    int insufficientCount = 0;
    for (String buyerKey : targets) {
      SourceBuyerProfile profile = roster.get(buyerKey);
      List<BuyerOrder> buyerOrders = byBuyer.getOrDefault(buyerKey, List.of());
      BuyerRiskScoreView view =
          scoreOne(
              tenant,
              rule,
              reference,
              buyerKey,
              profile,
              buyerOrders,
              orders.available(),
              orders.orders().size(),
              unresolved,
              unresolvedNotes,
              refunds,
              cases,
              bearer);
      scores.add(view);
      if (view.riskScore() == null) {
        insufficientCount++;
      } else {
        scoredCount++;
      }
    }

    return new BuyerRiskEvaluateResult(
        reference,
        rule.ruleName(),
        rule.version(),
        rule.missingSignalPolicy(),
        sourceStates,
        orders.available() ? orders.orders().size() : 0,
        unresolved,
        scores.size(),
        scoredCount,
        insufficientCount,
        scores);
  }

  /** 给一个买家算分并落库。 */
  private BuyerRiskScoreView scoreOne(
      UUID tenant,
      RuleRow rule,
      OffsetDateTime reference,
      String buyerKey,
      SourceBuyerProfile profile,
      List<BuyerOrder> buyerOrders,
      boolean ordersReadable,
      int ordersScanned,
      int unresolved,
      List<String> unresolvedNotes,
      SourceRefundRefs refunds,
      SourceFraudCaseRefs cases,
      String bearer) {

    int orderCount = buyerOrders.size();
    int refundedCount = 0;
    int fraudCaseCount = 0;
    Set<String> distinctAddresses = new LinkedHashSet<>();
    int anomalyCount = 0;
    int burstCount = 0;

    if (orderCount > 0) {
      for (BuyerOrder buyerOrder : buyerOrders) {
        if (refunds.available() && refunds.refundedOrderIds().contains(buyerOrder.order().orderId())) {
          refundedCount++;
        }
        if (cases.available() && cases.fraudReturnOrderIds().contains(buyerOrder.order().orderId())) {
          fraudCaseCount++;
        }
        SourceOrderFacts facts = buyerOrder.facts();
        distinctAddresses.add(
            facts.countryCode()
                + '|'
                + facts.postcode()
                + '|'
                + facts.city()
                + '|'
                + facts.district()
                + '|'
                + facts.addressLine1());
        if (addressAnomaly(facts) != null) {
          anomalyCount++;
        }
      }
      burstCount = maxOrdersInWindow(buyerOrders, rule.orderBurstWindowHours());
    }

    // ---- 逐特征 ----
    List<Feature> features = new ArrayList<>();
    features.add(refundFeature(rule, orderCount, refundedCount, refunds, ordersReadable, unresolved));
    features.add(disputeFeature());
    features.add(fraudFeature(rule, profile, orderCount, fraudCaseCount, cases));
    features.add(
        addressFeature(rule, orderCount, distinctAddresses.size(), anomalyCount, ordersReadable, unresolved));
    features.add(behaviorFeature(rule, orderCount, burstCount, ordersReadable, unresolved));
    features.add(relatedAccountsFeature());

    Outcome outcome =
        aggregate(
            features,
            dimension -> rule.weightOf(dimension),
            rule.missingSignalPolicy(),
            rule.manualReviewThreshold(),
            rule.holdThreshold());

    // ---- 落库（append-only）----
    UUID scoreId = Ids.next();
    String buyerName = profile == null ? "" : profile.buyerName();
    String buyerPhone = profile == null ? "" : profile.buyerPhone();
    String buyerEmail = profile == null ? "" : profile.buyerEmail();
    String buyerLevel = profile == null ? "" : profile.buyerLevel();
    boolean blacklisted = profile != null && profile.blacklisted();
    String blacklistReason = profile == null ? "" : profile.blacklistReason();
    // 🔴 没有画像的买家（只在订单里出现过）也有可读身份 —— 取第一张订单的收件人三件套，
    //    不让界面显示一个「无名买家」。
    if (profile == null && !buyerOrders.isEmpty()) {
      SourceOrderFacts facts = buyerOrders.get(0).facts();
      buyerName = facts.recipientName();
      buyerPhone = facts.recipientPhone();
      buyerEmail = facts.recipientEmail();
    }

    String note =
        "统计窗口 "
            + rule.orderLookbackDays()
            + " 天；本轮扫到 "
            + ordersScanned
            + " 张订单，其中 "
            + unresolved
            + " 张的收货地址读不到"
            + (unresolvedNotes.isEmpty() ? "" : "（" + String.join("；", unresolvedNotes) + "）")
            + "。";

    sql.execute(
        """
        insert into buyer_risk_score
          (id, tenant_id, buyer_key, buyer_name, buyer_phone, buyer_email, buyer_level,
           blacklisted, blacklist_reason, risk_score, evaluation_status, recommended_action,
           scored_dimension_count, missing_dimension_count, rule_id, rule_version, rule_name,
           missing_signal_policy, evaluated_reference_at, orders_scanned, order_count,
           refunded_order_count, lookback_days, conclusion, note, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as numeric), ?, ?, ?, ?, ?, ?, ?, ?,
                ?::timestamptz, ?, ?, ?, ?, ?, ?, now())
        """,
        scoreId,
        tenant,
        buyerKey,
        buyerName,
        buyerPhone,
        buyerEmail,
        buyerLevel,
        blacklisted,
        blacklistReason,
        outcome.score(),
        outcome.evaluationStatus(),
        outcome.recommendedAction(),
        outcome.scoredDimensionCount(),
        outcome.missingDimensionCount(),
        rule.id(),
        rule.version(),
        rule.ruleName(),
        rule.missingSignalPolicy(),
        reference,
        ordersScanned,
        orderCount,
        refundedCount,
        rule.orderLookbackDays(),
        outcome.conclusion(),
        note);

    for (Feature feature : features) {
      BigDecimal weight = rule.weightOf(feature.dimension());
      BigDecimal contribution = outcome.contributions().get(feature.dimension());
      sql.execute(
          """
          insert into buyer_risk_feature
            (id, tenant_id, score_id, dimension_code, feature_code, feature_label, available,
             raw_value, normalized_value, weight, contribution, reason, owner_module, note, created_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as numeric), ?, cast(? as numeric), ?, ?, ?, now())
          """,
          Ids.next(),
          tenant,
          scoreId,
          feature.dimension().name(),
          feature.dimension().featureCode(),
          feature.dimension().label(),
          feature.available(),
          feature.rawValue(),
          feature.normalizedValue(),
          weight,
          contribution,
          feature.reason(),
          feature.ownerModule(),
          feature.note());
    }

    // ---- 回写 9.2.2.2（尽力而为，失败不阻塞）----
    List<BuyerRiskWritebackView> writebacks = new ArrayList<>();
    writebacks.add(writebackSignal(tenant, scoreId, buyerKey, "REFUND_RATE", features, bearer));
    writebacks.add(writebackSignal(tenant, scoreId, buyerKey, "DISPUTE_RATE", features, bearer));

    return new BuyerRiskScoreView(
        scoreId,
        buyerKey,
        buyerName,
        buyerPhone,
        buyerEmail,
        buyerLevel,
        blacklisted,
        blacklistReason,
        outcome.score(),
        outcome.evaluationStatus(),
        outcome.recommendedAction(),
        outcome.scoredDimensionCount(),
        outcome.missingDimensionCount(),
        rule.id(),
        rule.version(),
        rule.ruleName(),
        rule.missingSignalPolicy(),
        reference,
        ordersScanned,
        orderCount,
        refundedCount,
        rule.orderLookbackDays(),
        outcome.conclusion(),
        note,
        reference,
        featureViews(features, outcome, rule),
        writebacks);
  }

  // ================================================================ 特征计算

  private Feature refundFeature(
      RuleRow rule,
      int orderCount,
      int refundedCount,
      SourceRefundRefs refunds,
      boolean ordersReadable,
      int unresolved) {
    String raw = "orders=" + orderCount + ";refunded=" + refundedCount;
    if (!ordersReadable) {
      return new Feature(
          Dimension.REFUND_HISTORY,
          false,
          raw,
          null,
          "订单列表读不到 ⇒ 退款率量不到。没有拿 0 冒充「退款率 0」。",
          "9.1.1.1",
          "上游读面失败");
    }
    if (unresolved > 0) {
      return new Feature(
          Dimension.REFUND_HISTORY,
          false,
          raw,
          null,
          "本轮有 " + unresolved + " 张订单的收货地址读不到，订单集合不完整 ⇒ 退款率量不到"
              + "（拿不完整的集合算出来的退款率会偏低，不能当成结论）。",
          "9.1.1.1",
          "上游读面不完整");
    }
    if (orderCount == 0) {
      return new Feature(
          Dimension.REFUND_HISTORY,
          false,
          raw,
          null,
          "统计窗口内这个买家没有订单 ⇒ 没有分母，退款率量不到。",
          "9.1.1.1",
          "没有订单");
    }
    if (!refunds.available()) {
      return new Feature(
          Dimension.REFUND_HISTORY,
          false,
          raw,
          null,
          "退款数据读不到（" + refunds.note() + "）⇒ 退款率量不到。没有拿 0 冒充「退款率 0」。",
          "11.3.1.2",
          "上游读面失败");
    }
    BigDecimal rate =
        BigDecimal.valueOf(refundedCount)
            .divide(BigDecimal.valueOf(orderCount), 6, RoundingMode.HALF_UP);
    BigDecimal normalized = byThreshold(rate, rule.refundRateFullRisk());
    boolean capped = normalized.compareTo(BigDecimal.ONE) >= 0 && rate.compareTo(rule.refundRateFullRisk()) > 0;
    String reason =
        "统计窗口内这个买家 "
            + orderCount
            + " 笔订单中有 "
            + refundedCount
            + " 笔发起过退款（退款率 "
            + percentText(rate)
            + "，满档阈值 "
            + percentText(rule.refundRateFullRisk())
            + "）"
            + (capped ? "，已超阈值，按满档计分。" : "。");
    return new Feature(
        Dimension.REFUND_HISTORY, true, raw + ";rate=" + rate.toPlainString(), normalized, reason, "", "");
  }

  private Feature disputeFeature() {
    return new Feature(
        Dimension.DISPUTE_HISTORY,
        false,
        "join=missing",
        null,
        "🔴 纠纷率**本轮量不到**：平台纠纷在本仓没有买家 / 订单 join 键 —— trade 的 platform_dispute "
            + "只有一个自由文本 platform_order_no，既没有 order_id 也没有任何买家标识。"
            + "用字符串匹配凑出来的纠纷率看似有数、实则不可信 ⇒ 本维度不参与加权，"
            + "**没有拿 0 冒充「纠纷率 0」**。",
        "11.4.1.1",
        "需要平台纠纷补上订单 / 买家 join 键");
  }

  private Feature fraudFeature(
      RuleRow rule,
      SourceBuyerProfile profile,
      int orderCount,
      int fraudCaseCount,
      SourceFraudCaseRefs cases) {
    String raw =
        "profile=" + (profile == null ? "none" : profile.buyerLevel())
            + ";blacklisted="
            + (profile != null && profile.blacklisted())
            + ";fraudCases="
            + fraudCaseCount;
    if (profile == null) {
      return new Feature(
          Dimension.FRAUD_PROFILE,
          false,
          raw,
          null,
          "这个买家**没有登记买家画像**（租户未维护）⇒ 欺诈特征量不到。"
              + "**没有画像不等于干净买家** —— 拉黑与等级都无从判断。",
          "9.2.1.2",
          "先去 trade 的买家画像登记这个买家");
    }
    BigDecimal profileRisk =
        profile.blacklisted()
            ? rule.fraudBlacklistedScore()
            : ("RISK".equals(profile.buyerLevel()) ? rule.fraudRiskLevelScore() : BigDecimal.ZERO);
    BigDecimal normalized = profileRisk;
    StringBuilder reason =
        new StringBuilder()
            .append("已登记买家画像（等级 ")
            .append(profile.buyerLevel())
            .append(profile.blacklisted() ? "、**已拉黑**" : "、未拉黑")
            .append("）⇒ 欺诈特征 ")
            .append(percentText(profileRisk))
            .append("。");
    if (!cases.available()) {
      reason.append("售后单读不到（").append(cases.note()).append("），「欺诈退货」子项未计入。");
    } else if (orderCount > 0) {
      BigDecimal rate =
          BigDecimal.valueOf(fraudCaseCount)
              .divide(BigDecimal.valueOf(orderCount), 6, RoundingMode.HALF_UP);
      BigDecimal caseRisk = byThreshold(rate, rule.fraudCaseFullRisk());
      reason
          .append("统计窗口内 ")
          .append(orderCount)
          .append(" 笔订单中有 ")
          .append(fraudCaseCount)
          .append(" 笔以「欺诈退货」为由的售后单（占比 ")
          .append(percentText(rate))
          .append("，满档阈值 ")
          .append(percentText(rule.fraudCaseFullRisk()))
          .append("）⇒ 该子项 ")
          .append(percentText(caseRisk))
          .append("。");
      if (caseRisk.compareTo(normalized) > 0) {
        normalized = caseRisk;
      }
    }
    reason.append("该维度取较高值 ").append(percentText(normalized)).append("。");
    return new Feature(
        Dimension.FRAUD_PROFILE,
        true,
        raw + ";risk=" + normalized.toPlainString(),
        normalized,
        reason.toString(),
        "",
        "");
  }

  private Feature addressFeature(
      RuleRow rule,
      int orderCount,
      int distinctAddresses,
      int anomalyCount,
      boolean ordersReadable,
      int unresolved) {
    String raw =
        "orders=" + orderCount + ";distinctAddresses=" + distinctAddresses + ";anomalies=" + anomalyCount;
    if (!ordersReadable) {
      return new Feature(
          Dimension.ADDRESS_PATTERN,
          false,
          raw,
          null,
          "订单列表读不到 ⇒ 地址模式量不到。",
          "9.1.1.1",
          "上游读面失败");
    }
    if (unresolved > 0) {
      return new Feature(
          Dimension.ADDRESS_PATTERN,
          false,
          raw,
          null,
          "本轮有 " + unresolved + " 张订单的收货地址读不到，地址集合不完整 ⇒ 地址模式量不到。",
          "9.1.1.1",
          "上游读面不完整");
    }
    if (orderCount == 0) {
      return new Feature(
          Dimension.ADDRESS_PATTERN,
          false,
          raw,
          null,
          "统计窗口内这个买家没有订单 ⇒ 没有地址可读，地址模式量不到。",
          "9.1.1.1",
          "没有订单");
    }
    BigDecimal ratio =
        BigDecimal.valueOf(distinctAddresses)
            .divide(BigDecimal.valueOf(orderCount), 6, RoundingMode.HALF_UP);
    BigDecimal spreadRisk = aboveThreshold(ratio, rule.addressSingleRatioThreshold());
    BigDecimal anomalyRisk =
        BigDecimal.valueOf(anomalyCount)
            .divide(BigDecimal.valueOf(orderCount), 6, RoundingMode.HALF_UP);
    BigDecimal normalized = spreadRisk.max(anomalyRisk);
    String reason =
        "统计窗口内 "
            + orderCount
            + " 笔订单寄往 "
            + distinctAddresses
            + " 个不同地址（地址分散度 "
            + percentText(ratio)
            + "，阈值 "
            + percentText(rule.addressSingleRatioThreshold())
            + " ⇒ 分散风险 "
            + percentText(spreadRisk)
            + "）；其中 "
            + anomalyCount
            + " 笔订单的收货地址缺邮编或缺电话（占 "
            + percentText(anomalyRisk)
            + "）⇒ 该维度取较高值 "
            + percentText(normalized)
            + "。";
    return new Feature(
        Dimension.ADDRESS_PATTERN,
        true,
        raw + ";spread=" + ratio.toPlainString(),
        normalized,
        reason,
        "",
        "");
  }

  private Feature behaviorFeature(
      RuleRow rule, int orderCount, int burstCount, boolean ordersReadable, int unresolved) {
    String raw = "orders=" + orderCount + ";burst=" + burstCount;
    if (!ordersReadable) {
      return new Feature(
          Dimension.ORDER_BEHAVIOR,
          false,
          raw,
          null,
          "订单列表读不到 ⇒ 下单行为量不到。",
          "9.1.1.1",
          "上游读面失败");
    }
    if (unresolved > 0) {
      return new Feature(
          Dimension.ORDER_BEHAVIOR,
          false,
          raw,
          null,
          "本轮有 " + unresolved + " 张订单的收货地址读不到，订单集合不完整 ⇒ 下单行为量不到。",
          "9.1.1.1",
          "上游读面不完整");
    }
    if (orderCount == 0) {
      return new Feature(
          Dimension.ORDER_BEHAVIOR,
          false,
          raw,
          null,
          "统计窗口内这个买家没有订单 ⇒ 下单行为量不到。",
          "9.1.1.1",
          "没有订单");
    }
    BigDecimal frequency =
        BigDecimal.valueOf(orderCount)
            .multiply(BigDecimal.valueOf(30))
            .divide(BigDecimal.valueOf(rule.orderLookbackDays()), 6, RoundingMode.HALF_UP);
    BigDecimal frequencyRisk = byThreshold(frequency, rule.orderFrequencyFullRisk());
    BigDecimal burstRisk =
        byThreshold(BigDecimal.valueOf(burstCount), BigDecimal.valueOf(rule.orderBurstThreshold()));
    BigDecimal normalized = frequencyRisk.max(burstRisk);
    String reason =
        "统计窗口（"
            + rule.orderLookbackDays()
            + " 天）内这个买家下过 "
            + orderCount
            + " 笔订单（折合每 30 天 "
            + frequency.setScale(2, RoundingMode.HALF_UP).toPlainString()
            + " 笔，满档 "
            + rule.orderFrequencyFullRisk().toPlainString()
            + " 笔）；最大 "
            + rule.orderBurstWindowHours()
            + " 小时内 "
            + burstCount
            + " 笔（满档 "
            + rule.orderBurstThreshold()
            + " 笔）⇒ 该维度取较高值 "
            + percentText(normalized)
            + "。";
    return new Feature(
        Dimension.ORDER_BEHAVIOR,
        true,
        raw + ";frequency=" + frequency.toPlainString(),
        normalized,
        reason,
        "",
        "");
  }

  private Feature relatedAccountsFeature() {
    return new Feature(
        Dimension.RELATED_ACCOUNTS,
        false,
        "owner=16.5.2.2",
        null,
        "🔴 **未交付**：16.5.2.2 关联风险没有落地，本模块**不编一张「关联账号」表**。"
            + "该维度**刻意没有权重** —— 一个永远量不到的维度占着权重会稀释分数。",
        "16.5.2.2",
        "等 16.5.2.2 落地后带着自己的维度与权重进来");
  }

  /** 收货地址的字段缺失（**只算这两个**：trade 的完整异常词表还有别的码，本模块不猜）。 */
  private static String addressAnomaly(SourceOrderFacts facts) {
    if (facts.postcode().isBlank()) return "MISSING_POSTCODE";
    if (facts.recipientPhone().isBlank()) return "MISSING_PHONE";
    return null;
  }

  /** 任意时间窗内最多的订单笔数（滑动窗口）。 */
  private static int maxOrdersInWindow(List<BuyerOrder> orders, int windowHours) {
    if (orders.isEmpty()) return 0;
    List<OffsetDateTime> times = new ArrayList<>();
    for (BuyerOrder order : orders) {
      if (order.order().createdAt() != null) times.add(order.order().createdAt());
    }
    if (times.isEmpty()) return 0;
    times.sort(Comparator.naturalOrder());
    int best = 0;
    for (int start = 0; start < times.size(); start++) {
      int end = start;
      while (end < times.size()
          && !times.get(end).isAfter(times.get(start).plusHours(windowHours))) {
        end++;
      }
      best = Math.max(best, end - start);
    }
    return best;
  }

  // ================================================================ 回写

  private BuyerRiskWritebackView writebackSignal(
      UUID tenant,
      UUID scoreId,
      String buyerKey,
      String signalCode,
      List<Feature> features,
      String bearer) {
    Feature feature =
        features.stream()
            .filter(f -> f.dimension().featureCode().equals(signalCode))
            .findFirst()
            .orElse(null);
    if (feature == null || !feature.available()) {
      // 🔴 「跳过」也**留凭据**：它证明这一轮确实没有可回写的信号值（而不是压根没尝试）。
      //    measured_value 是 null —— 跳过不是 0，0 会被读成「信号值是 0」。
      sql.execute(
          """
          insert into buyer_risk_signal_writeback
            (id, tenant_id, score_id, feature_code, signal_code, subject_kind, subject_key,
             measured_value, writeback_status, writeback_note, attempted_at, created_at)
          values (?, ?, ?, ?, ?, 'BUYER_FINGERPRINT', ?, null, 'SKIPPED', ?, null, now())
          """,
          Ids.next(),
          tenant,
          scoreId,
          signalCode,
          signalCode,
          buyerKey,
          feature == null
              ? "本轮没有这个特征，没有可回写的信号值。"
              : "本轮这个特征量不到，没有可回写的信号值。");
      return new BuyerRiskWritebackView(
          signalCode,
          signalCode,
          buyerKey,
          null,
          "SKIPPED",
          feature == null
              ? "本轮没有这个特征，没有可回写的信号值。"
              : "本轮这个特征量不到，没有可回写的信号值。",
          null);
    }
    BigDecimal rate = BigDecimal.ZERO;
    for (String part : feature.rawValue().split(";")) {
      if (part.startsWith("rate=")) {
        rate = new BigDecimal(part.substring("rate=".length()));
      }
    }
    BuyerRiskScoreSourcePort.SignalWritebackResult result =
        source.writeSignal(
            bearer,
            signalCode,
            buyerKey,
            rate,
            scoreId.toString(),
            dbNow(),
            "16.5.1.2 买家风险评分：" + feature.reason());
    OffsetDateTime attempted = dbNow();
    sql.execute(
        """
        insert into buyer_risk_signal_writeback
          (id, tenant_id, score_id, feature_code, signal_code, subject_kind, subject_key,
           measured_value, writeback_status, writeback_note, attempted_at, created_at)
        values (?, ?, ?, ?, ?, 'BUYER_FINGERPRINT', ?, ?, ?, ?, ?::timestamptz, now())
        """,
        Ids.next(),
        tenant,
        scoreId,
        signalCode,
        signalCode,
        buyerKey,
        rate,
        result.succeeded() ? "SUCCEEDED" : "FAILED",
        result.succeeded() ? "已回写 9.2.2.2 的风控信号。" : result.note(),
        attempted);
    return new BuyerRiskWritebackView(
        signalCode,
        signalCode,
        buyerKey,
        rate,
        result.succeeded() ? "SUCCEEDED" : "FAILED",
        result.succeeded() ? "已回写 9.2.2.2 的风控信号。" : result.note(),
        attempted);
  }

  // ================================================================ 评分查询

  /** 评分历史（真分页 + 真筛选 + 与分页无关的汇总）。 */
  public BuyerRiskScorePage scores(
      String buyerKey, String status, String action, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?");
    binds.add(tenant);
    if (buyerKey != null && !buyerKey.isBlank()) {
      where.add("buyer_key = ?");
      binds.add(buyerKey.trim().toLowerCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.add("evaluation_status = ?");
      binds.add(status.trim());
    }
    if (action != null && !action.isBlank()) {
      where.add("recommended_action = ?");
      binds.add(action.trim());
    }
    String clause = " where " + String.join(" and ", where);

    long total = ((Number) sql.fetchValue("select count(*) from buyer_risk_score" + clause, binds.toArray()))
        .longValue();

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add((long) safePage * safeSize);
    List<BuyerRiskScoreSummary> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, buyer_key, buyer_name, buyer_email, buyer_level, risk_score,"
                + " evaluation_status, recommended_action, scored_dimension_count,"
                + " missing_dimension_count, order_count, refunded_order_count,"
                + " evaluated_reference_at, created_at"
                + " from buyer_risk_score"
                + clause
                + " order by created_at desc, id desc limit ? offset ?",
            pageBinds.toArray())) {
      items.add(
          new BuyerRiskScoreSummary(
              row.get("id", UUID.class),
              row.get("buyer_key", String.class),
              row.get("buyer_name", String.class),
              row.get("buyer_email", String.class),
              row.get("buyer_level", String.class),
              row.get("risk_score", BigDecimal.class),
              row.get("evaluation_status", String.class),
              row.get("recommended_action", String.class),
              row.get("scored_dimension_count", Integer.class),
              row.get("missing_dimension_count", Integer.class),
              row.get("order_count", Integer.class),
              row.get("refunded_order_count", Integer.class),
              row.get("evaluated_reference_at", OffsetDateTime.class),
              row.get("created_at", OffsetDateTime.class)));
    }

    // 🔴 多列聚合必须用 fetchOne（fetchValue 只取第一列，jOOQ 会把它当单值返回）。
    Record countsRow =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where evaluation_status = 'SCORED') as scored,"
                + " count(*) filter (where evaluation_status = 'INSUFFICIENT_SIGNALS') as insufficient,"
                + " count(*) filter (where recommended_action = 'RELEASE') as release,"
                + " count(*) filter (where recommended_action = 'MANUAL_REVIEW') as manual_review,"
                + " count(*) filter (where recommended_action = 'HOLD') as hold"
                + " from buyer_risk_score where tenant_id = ?",
            tenant);
    BuyerRiskScoreSummaryCounts summary =
        new BuyerRiskScoreSummaryCounts(
            countsRow.get("total", Long.class),
            countsRow.get("scored", Long.class),
            countsRow.get("insufficient", Long.class),
            countsRow.get("release", Long.class),
            countsRow.get("manual_review", Long.class),
            countsRow.get("hold", Long.class));

    return new BuyerRiskScorePage(items, safePage, safeSize, total, summary);
  }

  /** 单次评分详情（头 + 逐特征明细 + 回写凭据）。 */
  public BuyerRiskScoreView score(String id) {
    UUID tenant = currentTenant();
    UUID target = parseUuid(id);
    if (target == null) throw notFound();
    Record head =
        sql.fetchOne(
            "select id, buyer_key, buyer_name, buyer_phone, buyer_email, buyer_level, blacklisted,"
                + " blacklist_reason, risk_score, evaluation_status, recommended_action,"
                + " scored_dimension_count, missing_dimension_count, rule_id, rule_version, rule_name,"
                + " missing_signal_policy, evaluated_reference_at, orders_scanned, order_count,"
                + " refunded_order_count, lookback_days, conclusion, note, created_at"
                + " from buyer_risk_score where tenant_id = ? and id = ?",
            tenant,
            target);
    if (head == null) throw notFound();

    List<BuyerRiskFeatureView> features = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, dimension_code, feature_code, feature_label, available, raw_value,"
                + " normalized_value, weight, contribution, reason, owner_module, note"
                + " from buyer_risk_feature where tenant_id = ? and score_id = ?"
                + " order by dimension_code",
            tenant,
            target)) {
      features.add(
          new BuyerRiskFeatureView(
              row.get("id", UUID.class),
              row.get("dimension_code", String.class),
              labelOf(row.get("dimension_code", String.class)),
              row.get("feature_code", String.class),
              row.get("feature_label", String.class),
              row.get("available", Boolean.class),
              row.get("raw_value", String.class),
              row.get("normalized_value", BigDecimal.class),
              row.get("weight", BigDecimal.class),
              row.get("contribution", BigDecimal.class),
              row.get("reason", String.class),
              row.get("owner_module", String.class),
              row.get("note", String.class)));
    }

    List<BuyerRiskWritebackView> writebacks = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select feature_code, signal_code, subject_key, measured_value, writeback_status,"
                + " writeback_note, attempted_at"
                + " from buyer_risk_signal_writeback where tenant_id = ? and score_id = ?"
                + " order by feature_code",
            tenant,
            target)) {
      writebacks.add(
          new BuyerRiskWritebackView(
              row.get("feature_code", String.class),
              row.get("signal_code", String.class),
              row.get("subject_key", String.class),
              row.get("measured_value", BigDecimal.class),
              row.get("writeback_status", String.class),
              row.get("writeback_note", String.class),
              row.get("attempted_at", OffsetDateTime.class)));
    }

    return new BuyerRiskScoreView(
        head.get("id", UUID.class),
        head.get("buyer_key", String.class),
        head.get("buyer_name", String.class),
        head.get("buyer_phone", String.class),
        head.get("buyer_email", String.class),
        head.get("buyer_level", String.class),
        head.get("blacklisted", Boolean.class),
        head.get("blacklist_reason", String.class),
        head.get("risk_score", BigDecimal.class),
        head.get("evaluation_status", String.class),
        head.get("recommended_action", String.class),
        head.get("scored_dimension_count", Integer.class),
        head.get("missing_dimension_count", Integer.class),
        head.get("rule_id", UUID.class),
        head.get("rule_version", Long.class),
        head.get("rule_name", String.class),
        head.get("missing_signal_policy", String.class),
        head.get("evaluated_reference_at", OffsetDateTime.class),
        head.get("orders_scanned", Integer.class),
        head.get("order_count", Integer.class),
        head.get("refunded_order_count", Integer.class),
        head.get("lookback_days", Integer.class),
        head.get("conclusion", String.class),
        head.get("note", String.class),
        head.get("created_at", OffsetDateTime.class),
        features,
        writebacks);
  }

  // ================================================================ 买家名册

  /**
   * 买家名册（来自 trade 的租户自维护画像）+ 每个买家的最近一次评分。
   *
   * <p>🔴 读不到上游时返回 {@code available=false} + 人话原因，**不返回空集合冒充「这个租户没有买家」**。
   */
  public BuyerRiskBuyerPage buyers(String bearer, String keyword, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = Math.max(0, page);
    int safeSize = Math.min(200, Math.max(1, size));
    SourceBuyerProfiles profiles = source.buyerProfiles(bearer);
    if (!profiles.available()) {
      return new BuyerRiskBuyerPage(
          false, profiles.note(), List.of(), safePage, safeSize, 0);
    }
    List<SourceBuyerProfile> all = new ArrayList<>();
    for (SourceBuyerProfile profile : profiles.profiles()) {
      if (keyword != null && !keyword.isBlank() && !matches(profile, keyword)) continue;
      all.add(profile);
    }
    all.sort(Comparator.comparing(SourceBuyerProfile::buyerName));
    int from = Math.min(all.size(), safePage * safeSize);
    int to = Math.min(all.size(), from + safeSize);

    Map<String, Record> latest = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            "select distinct on (buyer_key) buyer_key, id, risk_score, evaluation_status,"
                + " recommended_action"
                + " from buyer_risk_score where tenant_id = ?"
                + " order by buyer_key, created_at desc, id desc",
            tenant)) {
      latest.put(row.get("buyer_key", String.class), row);
    }

    List<BuyerRiskBuyerRow> items = new ArrayList<>();
    for (SourceBuyerProfile profile : all.subList(from, to)) {
      Record score = latest.get(profile.buyerKey());
      items.add(
          new BuyerRiskBuyerRow(
              profile.buyerKey(),
              profile.buyerName(),
              profile.buyerPhone(),
              profile.buyerEmail(),
              profile.buyerLevel(),
              profile.blacklisted(),
              profile.blacklistReason(),
              score == null ? null : score.get("id", UUID.class),
              score == null ? null : score.get("risk_score", BigDecimal.class),
              score == null ? null : score.get("evaluation_status", String.class),
              score == null ? null : score.get("recommended_action", String.class)));
    }
    return new BuyerRiskBuyerPage(
        true, "", items, safePage, safeSize, all.size());
  }

  private static boolean matches(SourceBuyerProfile profile, String keyword) {
    String needle = keyword.trim().toLowerCase(Locale.ROOT);
    return profile.buyerName().toLowerCase(Locale.ROOT).contains(needle)
        || profile.buyerEmail().toLowerCase(Locale.ROOT).contains(needle)
        || profile.buyerPhone().toLowerCase(Locale.ROOT).contains(needle)
        || profile.buyerKey().contains(needle);
  }

  // ================================================================ 内部

  /** 一次读出的一条订单 + 它的收货地址事实。 */
  private record BuyerOrder(SourceOrder order, SourceOrderFacts facts) {}

  /** 规则一行。 */
  private record RuleRow(
      UUID id,
      String ruleName,
      BigDecimal weightRefundHistory,
      BigDecimal weightDisputeHistory,
      BigDecimal weightFraudProfile,
      BigDecimal weightAddressPattern,
      BigDecimal weightOrderBehavior,
      String missingSignalPolicy,
      BigDecimal manualReviewThreshold,
      BigDecimal holdThreshold,
      BigDecimal refundRateFullRisk,
      BigDecimal fraudBlacklistedScore,
      BigDecimal fraudRiskLevelScore,
      BigDecimal fraudCaseFullRisk,
      BigDecimal addressSingleRatioThreshold,
      int orderBurstWindowHours,
      int orderBurstThreshold,
      BigDecimal orderFrequencyFullRisk,
      int orderLookbackDays,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {

    BigDecimal weightOf(Dimension dimension) {
      return switch (dimension) {
        case REFUND_HISTORY -> weightRefundHistory;
        case DISPUTE_HISTORY -> weightDisputeHistory;
        case FRAUD_PROFILE -> weightFraudProfile;
        case ADDRESS_PATTERN -> weightAddressPattern;
        case ORDER_BEHAVIOR -> weightOrderBehavior;
        case RELATED_ACCOUNTS -> BigDecimal.ZERO;
      };
    }

    BuyerRiskRuleView toView() {
      return new BuyerRiskRuleView(
          id,
          ruleName,
          weightRefundHistory,
          weightDisputeHistory,
          weightFraudProfile,
          weightAddressPattern,
          weightOrderBehavior,
          missingSignalPolicy,
          manualReviewThreshold,
          holdThreshold,
          refundRateFullRisk,
          fraudBlacklistedScore,
          fraudRiskLevelScore,
          fraudCaseFullRisk,
          addressSingleRatioThreshold,
          orderBurstWindowHours,
          orderBurstThreshold,
          orderFrequencyFullRisk,
          orderLookbackDays,
          version,
          true,
          createdAt,
          updatedAt);
    }
  }

  private RuleRow loadRule() {
    UUID tenant = currentTenant();
    Record row =
        sql.fetchOne(
            "select id, rule_name, weight_refund_history, weight_dispute_history,"
                + " weight_fraud_profile, weight_address_pattern, weight_order_behavior,"
                + " missing_signal_policy, manual_review_threshold, hold_threshold,"
                + " refund_rate_full_risk, fraud_blacklisted_score, fraud_risk_level_score,"
                + " fraud_case_full_risk, address_single_ratio_threshold,"
                + " order_burst_window_hours, order_burst_threshold, order_frequency_full_risk,"
                + " order_lookback_days, version, created_at, updated_at"
                + " from buyer_risk_rule where tenant_id = ?",
            tenant);
    if (row == null) return null;
    return new RuleRow(
        row.get("id", UUID.class),
        row.get("rule_name", String.class),
        row.get("weight_refund_history", BigDecimal.class),
        row.get("weight_dispute_history", BigDecimal.class),
        row.get("weight_fraud_profile", BigDecimal.class),
        row.get("weight_address_pattern", BigDecimal.class),
        row.get("weight_order_behavior", BigDecimal.class),
        row.get("missing_signal_policy", String.class),
        row.get("manual_review_threshold", BigDecimal.class),
        row.get("hold_threshold", BigDecimal.class),
        row.get("refund_rate_full_risk", BigDecimal.class),
        row.get("fraud_blacklisted_score", BigDecimal.class),
        row.get("fraud_risk_level_score", BigDecimal.class),
        row.get("fraud_case_full_risk", BigDecimal.class),
        row.get("address_single_ratio_threshold", BigDecimal.class),
        row.get("order_burst_window_hours", Integer.class),
        row.get("order_burst_threshold", Integer.class),
        row.get("order_frequency_full_risk", BigDecimal.class),
        row.get("order_lookback_days", Integer.class),
        row.get("version", Long.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 把特征结果转成视图。 */
  private List<BuyerRiskFeatureView> featureViews(
      List<Feature> features, Outcome outcome, RuleRow rule) {
    List<BuyerRiskFeatureView> views = new ArrayList<>();
    for (Feature feature : features) {
      views.add(
          new BuyerRiskFeatureView(
              null,
              feature.dimension().name(),
              feature.dimension().label(),
              feature.dimension().featureCode(),
              feature.dimension().label(),
              feature.available(),
              feature.rawValue(),
              feature.normalizedValue(),
              rule.weightOf(feature.dimension()),
              outcome.contributions().get(feature.dimension()),
              feature.reason(),
              feature.ownerModule(),
              feature.note()));
    }
    return views;
  }

  private static String labelOf(String dimensionCode) {
    for (Dimension dimension : Dimension.values()) {
      if (dimension.name().equals(dimensionCode)) return dimension.label();
    }
    return dimensionCode;
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /** 🔴 <b>同源 DB 时钟</b>：判定时刻一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static UUID parseUuid(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return UUID.fromString(raw.trim());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}
