package com.starcon.channel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 16.3.2.3 销售税与 Marketplace Facilitator 的**判定规则**（纯函数，不碰数据库、不碰 Spring）。
 *
 * <p>蓝图加粗的那一句是「<b>平台代扣代缴不等于免税</b>，卖家的申报义务依然存在，只是由平台代收代缴，
 * 需要正确对账」。所以「应缴多少、平台代缴多少、差多少」这件事必须<b>可审阅、可复算、
 * 可用单元测试钉住</b>，而不是散在 SQL 里的一堆 if。
 *
 * <p>🔴 判定口径的八条不变量：
 *
 * <ol>
 *   <li><b>应缴与已代缴是两条金额线</b>：{@code expectedTax}（卖家欠税局的）与
 *       {@code facilitatorTax}（平台替收的）分开存，差异单独存 ——
 *       <b>不许压成一个净额</b>：压了就看不出「平台少收了要补缴」与「平台多收了要退款」。</li>
 *   <li><b>规则是数据不是常量</b>：税率 / 州级阈值 / 免征额来自 {@code sales_tax_rate_rule}
 *       （按州 × 生效期间版本化），本类<b>不持有任何一张州税率表</b>。</li>
 *   <li><b>没有生效规则 ⇒ 判不了</b>（{@code RULE_MISSING}），<b>不许</b>默认按 0 税率算 ——
 *       「按 0 算」看起来像「这个州不用交税」，那是最贵的假结论。</li>
 *   <li><b>没到阈值 ⇒ 没有申报义务</b>（{@code BELOW_THRESHOLD}），应缴记 0 ——
 *       但<b>不</b>等于「免税」，所以状态与「免征额内」是分开的两件事。</li>
 *   <li><b>免征额只减基数</b>：落在免征额内的部分记进 {@code exemptSales}，
 *       <b>不是免税</b> —— 超过阈值后它就整体进应税基数。</li>
 *   <li><b>不同币种绝不直接相加</b>：对账按币种分开存、分开比。</li>
 *   <li><b>规则版本快照</b>：对账结果记下当时用的版本号与税率 ⇒ 事后改规则改不动历史判定。</li>
 *   <li><b>上游读不到就不产出结论</b>：自营应税销售额来自 12.3.1.2 的授权 HTTP API，
 *       读不到 ⇒ <b>显式失败</b>，绝不拿 0 当「这段时间没卖货」糊弄过去。</li>
 * </ol>
 */
public final class SalesTaxRules {

  private SalesTaxRules() {}

  // ---------------------------------------------------------------- 联系类型词表

  /** 联系类型：物理存在（有实体门店 / 办公地址在该州）。 */
  public static final String NEXUS_PHYSICAL_PRESENCE = "PHYSICAL_PRESENCE";

  /** 联系类型：库存存放（货仓 / FBA 仓在该州）。 */
  public static final String NEXUS_INVENTORY_STORED = "INVENTORY_STORED";

  /** 联系类型：经济联系（在该州有销售额达到阈值）。 */
  public static final String NEXUS_ECONOMIC_NEXUS = "ECONOMIC_NEXUS";

  /** 联系类型：仅平台代收（卖家在该州没有自己的联系，是平台代扣代缴收的）。 */
  public static final String NEXUS_MARKETPLACE_FACILITATOR = "MARKETPLACE_FACILITATOR";

  /** 🔴 联系类型值域：**四种**，钉死在 DB 的 CHECK 里。新增走 DB 迁移，不许运行时塞字符串。 */
  public static final List<String> NEXUS_TYPES =
      List.of(
          NEXUS_PHYSICAL_PRESENCE,
          NEXUS_INVENTORY_STORED,
          NEXUS_ECONOMIC_NEXUS,
          NEXUS_MARKETPLACE_FACILITATOR);

  // ---------------------------------------------------------------- 申报周期词表

  /** 申报周期：按月。 */
  public static final String CYCLE_MONTHLY = "MONTHLY";

  /** 申报周期：按季。 */
  public static final String CYCLE_QUARTERLY = "QUARTERLY";

  /** 申报周期：半年。 */
  public static final String CYCLE_SEMI_ANNUAL = "SEMI_ANNUAL";

  /** 申报周期：按年。 */
  public static final String CYCLE_ANNUAL = "ANNUAL";

  public static final List<String> FILING_CYCLES =
      List.of(CYCLE_MONTHLY, CYCLE_QUARTERLY, CYCLE_SEMI_ANNUAL, CYCLE_ANNUAL);

  // ---------------------------------------------------------------- 对账状态词表

  /** 对账：差异为 0。 */
  public static final String RECON_BALANCED = "BALANCED";

  /** 对账：有差异（正数 = 平台少收了，卖家要补缴；负数 = 平台多收了）。 */
  public static final String RECON_VARIANCE = "VARIANCE";

  /** 对账：未达州级阈值 ⇒ 期间无申报义务。 */
  public static final String RECON_BELOW_THRESHOLD = "BELOW_THRESHOLD";

  /** 对账：期间既无自营销售也无平台代缴。 */
  public static final String RECON_NO_ACTIVITY = "NO_ACTIVITY";

  /** 对账：没有生效的规则 ⇒ **判不了**（不许默认按 0 税率算）。 */
  public static final String RECON_RULE_MISSING = "RULE_MISSING";

  /** 对账：该州没登记任何店铺的销售税联系 ⇒ 自营销售额无法归集 ⇒ **判不了**。 */
  public static final String RECON_NEXUS_MISSING = "NEXUS_MISSING";

  public static final List<String> RECONCILIATION_STATUSES =
      List.of(
          RECON_BALANCED,
          RECON_VARIANCE,
          RECON_BELOW_THRESHOLD,
          RECON_NO_ACTIVITY,
          RECON_RULE_MISSING,
          RECON_NEXUS_MISSING);

  /** 对账状态的人话（界面直接显示，不让前端自己翻译）。 */
  public static String reconciliationStatusLabel(String status) {
    return switch (status == null ? "" : status) {
      case RECON_BALANCED -> "已对平（应缴 = 平台已代缴）";
      case RECON_VARIANCE -> "有差异（应缴 ≠ 平台已代缴）";
      case RECON_BELOW_THRESHOLD -> "未达州级阈值（期间无申报义务）";
      case RECON_NO_ACTIVITY -> "期间无销售也无代缴";
      case RECON_RULE_MISSING -> "没有生效的税率规则（判不了）";
      case RECON_NEXUS_MISSING -> "该州没有登记店铺销售税联系（判不了）";
      default -> "未知状态";
    };
  }

  // ---------------------------------------------------------------- 申报义务词表

  /** 申报义务：有应缴，需要申报（平台代扣代缴**不**抵消这个义务）。 */
  public static final String OBLIGATION_FILING_REQUIRED = "FILING_REQUIRED";

  /** 申报义务：未达阈值 ⇒ 期间不需要申报。 */
  public static final String OBLIGATION_BELOW_THRESHOLD = "BELOW_THRESHOLD";

  /** 申报义务：期间没有销售也没有代缴。 */
  public static final String OBLIGATION_NO_ACTIVITY = "NO_ACTIVITY";

  /** 申报义务：没有生效规则 ⇒ 判不了。 */
  public static final String OBLIGATION_RULE_MISSING = "RULE_MISSING";

  /** 申报义务：州里还没有店铺登记销售税账号。 */
  public static final String OBLIGATION_NOT_REGISTERED = "NOT_REGISTERED";

  public static final List<String> FILING_OBLIGATIONS =
      List.of(
          OBLIGATION_FILING_REQUIRED,
          OBLIGATION_BELOW_THRESHOLD,
          OBLIGATION_NO_ACTIVITY,
          OBLIGATION_RULE_MISSING,
          OBLIGATION_NOT_REGISTERED);

  /** 申报义务的人话。 */
  public static String filingObligationLabel(String obligation) {
    return switch (obligation == null ? "" : obligation) {
      case OBLIGATION_FILING_REQUIRED -> "需要申报（平台代缴不抵消申报义务）";
      case OBLIGATION_BELOW_THRESHOLD -> "期间不需要申报（未达州级阈值）";
      case OBLIGATION_NO_ACTIVITY -> "期间不需要申报（无销售无代缴）";
      case OBLIGATION_RULE_MISSING -> "判不了（没有生效的税率规则）";
      case OBLIGATION_NOT_REGISTERED -> "州里还没有店铺登记销售税账号";
      default -> "未知";
    };
  }

  // ---------------------------------------------------------------- 登记状态词表

  /** 登记状态：该州所有店都已登记。 */
  public static final String REGISTRATION_REGISTERED = "REGISTERED";

  /** 登记状态：部分店已登记。 */
  public static final String REGISTRATION_PARTIAL = "PARTIAL";

  /** 登记状态：一家店都没登记。 */
  public static final String REGISTRATION_NOT_REGISTERED = "NOT_REGISTERED";

  public static final List<String> REGISTRATION_STATUSES =
      List.of(REGISTRATION_REGISTERED, REGISTRATION_PARTIAL, REGISTRATION_NOT_REGISTERED);

  // ---------------------------------------------------------------- 规则状态词表

  /** 规则状态：有生效的规则。 */
  public static final String RULE_FOUND = "RULE_FOUND";

  /** 规则状态：没有生效的规则 ⇒ 判不了。 */
  public static final String RULE_MISSING = "RULE_MISSING";

  // ---------------------------------------------------------------- 数值口径

  /** 金额 / 数量小数位（与 DB 的 numeric(19,6) 一致）。 */
  public static final int MONEY_SCALE = 6;

  /** 税率小数位（与 DB 的 numeric(9,6) 一致）。 */
  public static final int RATE_SCALE = 6;

  /**
   * 四舍五入到 {@link #MONEY_SCALE} 位（{@link RoundingMode#HALF_UP}）。
   *
   * <p>🔴 必须是 HALF_UP 而不是 HALF_EVEN：会计口径是「四舍五入」，银行家舍入会让
   * 「正好 .0000005」的税额少算一半，而且同样的数在不同笔数上舍入方向不一致 ——
   * 合计与逐笔对不上。
   */
  public static BigDecimal money(BigDecimal value) {
    if (value == null) {
      return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 税率舍入到 {@link #RATE_SCALE} 位。 */
  public static BigDecimal rate(BigDecimal value) {
    if (value == null) {
      return BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }
    return value.setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  // ---------------------------------------------------------------- 规则查找

  /**
   * 找出某一刻生效的那一版规则。
   *
   * <p>生效区间是**左闭右开** {@code [effective_from, effective_to)}；
   * {@code effective_to} 为空表示「当前这版还没被换掉」。
   *
   * @param rules 某州的全规则版本
   * @param asOf 判定时刻（按日）
   * @return 生效的那一版；没有则 {@code null}（**判不了，不许默认 0 税率**）
   */
  public static SalesTaxModels.SalesTaxRateRuleView effectiveRule(
      List<SalesTaxModels.SalesTaxRateRuleView> rules, LocalDate asOf) {
    if (rules == null || asOf == null) {
      return null;
    }
    return rules.stream()
        .filter(rule -> !rule.effectiveFrom().isAfter(asOf))
        .filter(rule -> rule.effectiveTo() == null || rule.effectiveTo().isAfter(asOf))
        // 同一刻有多版生效时取版本号最大的那版（正常情况下 DB 触发器保证不会重叠，
        // 这里只是防御：万一数据被手工改过，也要有一个确定的答案而不是随机挑一版）。
        .max(Comparator.comparingInt(SalesTaxModels.SalesTaxRateRuleView::ruleVersion))
        .orElse(null);
  }

  // ---------------------------------------------------------------- 对账计算

  /**
   * 一次对账的输入。
   *
   * @param selfTaxableSales 自营应税销售额
   * @param facilitatorTax 平台已代扣代缴的税额
   * @param thresholdAmount 州级阈值（0 = 该州不设阈值）
   * @param exemptionAmount 免征额（0 = 该州不设免征额）
   * @param baseRate 州基准税率（{@code null} ⇒ 没有生效规则 ⇒ 判不了）
   * @param ruleVersion 用的规则版本号（{@code null} ⇒ 没有生效规则）
   * @param nexusShopCount 该州有销售税联系的店铺数（0 ⇒ 自营销售额无法归集 ⇒ 判不了）
   */
  public record ReconciliationInput(
      BigDecimal selfTaxableSales,
      BigDecimal facilitatorTax,
      BigDecimal thresholdAmount,
      BigDecimal exemptionAmount,
      BigDecimal baseRate,
      Integer ruleVersion,
      int nexusShopCount) {}

  /**
   * 一次对账的结果。
   *
   * @param exemptSales 落在免征额内的部分（**不是免税**）
   * @param taxableBase 应税基数
   * @param expectedTax 应缴
   * @param facilitatorTax 已由平台代扣代缴
   * @param variance 差异 = 应缴 − 已代缴
   * @param status 对账状态
   */
  public record ReconciliationOutcome(
      BigDecimal exemptSales,
      BigDecimal taxableBase,
      BigDecimal expectedTax,
      BigDecimal facilitatorTax,
      BigDecimal variance,
      String status) {}

  /**
   * 🔴 一次对账的**唯一算式**（纯函数，可单元测试钉住）。
   *
   * <p>算式（顺序不能换）：
   *
   * <ol>
   *   <li><b>没有生效规则 ⇒ 判不了</b>（{@code RULE_MISSING}）：应缴记 0，
   *       但状态**明写「判不了」** —— 不许按 0 税率算出一个「这个州不用交税」的假结论。</li>
   *   <li><b>期间既无自营销售也无平台代缴 ⇒ 无活动</b>（{@code NO_ACTIVITY}）。</li>
   *   <li><b>该州没登记任何店铺的销售税联系 ⇒ 判不了</b>（{@code NEXUS_MISSING}）：
   *       自营应税销售额根本没法归集，「应缴 0」是「没归集」不是「不用交」。</li>
   *   <li><b>未达州级阈值 ⇒ 无义务</b>（{@code BELOW_THRESHOLD}）：应缴记 0。
   *       注意阈值比的是<b>自营应税销售额</b>（平台的代收额不是卖家的销售额）。</li>
   *   <li><b>免征额先减基数</b>：{@code exemptSales = min(自营应税销售额, 免征额)}，
   *       {@code taxableBase = 自营应税销售额 − exemptSales}。</li>
   *   <li><b>应缴 = 应税基数 × 税率</b>，四舍五入到 6 位。</li>
   *   <li><b>差异 = 应缴 − 已代缴</b>：正数 = 平台少收了（卖家要补缴），
   *       负数 = 平台多收了（卖家可能能退）。差异为 0 ⇒ {@code BALANCED}。</li>
   * </ol>
   */
  public static ReconciliationOutcome compute(ReconciliationInput in) {
    BigDecimal self = money(in.selfTaxableSales());
    BigDecimal facilitator = money(in.facilitatorTax());
    BigDecimal threshold = money(in.thresholdAmount());
    BigDecimal exemption = money(in.exemptionAmount());

    if (in.ruleVersion() == null || in.baseRate() == null) {
      // 🔴 判不了：应缴记 0，状态明写「没有生效的税率规则」。
      return new ReconciliationOutcome(
          BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          facilitator,
          money(facilitator.negate()),
          RECON_RULE_MISSING);
    }

    if (self.signum() == 0 && facilitator.signum() == 0) {
      return new ReconciliationOutcome(
          BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          facilitator,
          BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          RECON_NO_ACTIVITY);
    }

    if (in.nexusShopCount() <= 0) {
      // 🔴 判不了：这个州一家店的销售税联系都没登记 ⇒ 自营应税销售额根本没法归集，
      //   「应缴 0」是「没归集」不是「不用交」。此时平台代缴的钱无从解释 ⇒ 状态明写判不了。
      return new ReconciliationOutcome(
          BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          facilitator,
          money(facilitator.negate()),
          RECON_NEXUS_MISSING);
    }

    if (threshold.signum() > 0 && self.compareTo(threshold) < 0) {
      // 未达阈值 ⇒ 期间无申报义务，但平台已经代收的钱仍然是「已代缴」（可能需要退）。
      return new ReconciliationOutcome(
          BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          facilitator,
          money(facilitator.negate()),
          RECON_BELOW_THRESHOLD);
    }

    BigDecimal exempt = self.min(exemption).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal base = money(self.subtract(exempt));
    BigDecimal expected =
        money(base.multiply(rate(in.baseRate())).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    BigDecimal variance = money(expected.subtract(facilitator));
    String status = variance.signum() == 0 ? RECON_BALANCED : RECON_VARIANCE;
    return new ReconciliationOutcome(exempt, base, expected, facilitator, variance, status);
  }
}
