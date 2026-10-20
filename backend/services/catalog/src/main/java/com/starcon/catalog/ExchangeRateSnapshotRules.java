package com.starcon.catalog;

import com.starcon.catalog.ExchangeRateSnapshotModels.ConversionDirection;
import com.starcon.catalog.ExchangeRateSnapshotModels.DocumentType;
import com.starcon.catalog.ExchangeRateSnapshotModels.RoundingModeOption;
import com.starcon.catalog.ExchangeRateSnapshotModels.SnapshotInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 5.2.2.2 汇率快照的**纯计算**规则（无 IO，可单测）。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **汇率快照是利润可追溯的前提**：今天汇率变了不能让上个月的利润报表数字跟着变。
 *
 * </blockquote>
 *
 * <p>⇒ 这一条在代码里有**三个**落点，少一个都不算数：
 *
 * <ol>
 *   <li><b>存储层</b>：快照行把汇率、方向、生效日、来源、舍入方式、目标币金额
 *       **整行冻结**（V229 迁移第二、四节），读快照不 JOIN 汇率表取值。
 *   <li><b>计算层</b>：{@link #convert} 是**唯一**的换算入口，
 *       冻结时算一次、复算时算一次，两次走的是同一段代码 —— 复算才有意义。
 *   <li><b>显示层</b>：{@link #conversionDisplay} 产出整句换算过程，
 *       页面不允许把裸数字（汇率或金额）单独显示。
 * </ol>
 *
 * <h2>🔴 金额与汇率一律 BigDecimal，禁浮点</h2>
 *
 * <p>AGENTS.md：禁止浮点记账。金额是 {@code numeric(19,6)}、汇率是 {@code numeric(19,8)}，
 * 所以这里的每一次乘除都带 scale 与舍入模式。
 */
public final class ExchangeRateSnapshotRules {

  /** 金额精度：6 位小数（与 V229 的 {@code numeric(19,6)} 一致）。 */
  public static final int AMOUNT_SCALE = 6;

  /** 汇率精度：8 位小数（与 V225 的 {@code numeric(19,8)} 一致）。 */
  public static final int RATE_SCALE = 8;

  private ExchangeRateSnapshotRules() {}

  // ================================================================ 方向

  /**
   * 原价站在这条汇率的哪一侧。
   *
   * <p><b>🔴 方向由「基准币 / 报价币 / 原价币」三者唯一确定</b>，不靠约定：
   *
   * <ul>
   *   <li>原价币 == 基准币 ⇒ {@link ConversionDirection#BASE_TO_QUOTE}（乘）
   *   <li>原价币 == 报价币 ⇒ {@link ConversionDirection#QUOTE_TO_BASE}（除）
   *   <li>都不等 ⇒ 返回 null（调用方据此报错）
   * </ul>
   *
   * <p>「1 USD = X CNY 与 1 CNY = X USD 混淆是常见错源」（5.2.2.1 加粗句）在本模块继续成立：
   * 原价币必须落在这条汇率的两侧之一，否则报错，**不猜**。
   */
  public static ConversionDirection resolveDirection(
      String baseCurrency, String quoteCurrency, String sourceCurrency) {
    var base = ExchangeRateRules.normalizeCurrency(baseCurrency);
    var quote = ExchangeRateRules.normalizeCurrency(quoteCurrency);
    var source = ExchangeRateRules.normalizeCurrency(sourceCurrency);
    if (base == null || quote == null || source == null) return null;
    if (base.equals(source)) return ConversionDirection.BASE_TO_QUOTE;
    if (quote.equals(source)) return ConversionDirection.QUOTE_TO_BASE;
    return null;
  }

  /** 方向不合法时的说明。 */
  public static String directionProblem(String baseCurrency, String quoteCurrency, String sourceCurrency) {
    var base = ExchangeRateRules.normalizeCurrency(baseCurrency);
    var quote = ExchangeRateRules.normalizeCurrency(quoteCurrency);
    var source = ExchangeRateRules.normalizeCurrency(sourceCurrency);
    return "原价币种「"
        + (source == null ? "?" : source)
        + "」必须是这条汇率的基准币（"
        + (base == null ? "?" : base)
        + "）或报价币（"
        + (quote == null ? "?" : quote)
        + "）之一："
        + "1 "
        + (base == null ? "?" : base)
        + " = ? "
        + (quote == null ? "?" : quote)
        + " 这条汇率只能朝这两个方向换算，不猜方向。";
  }

  // ================================================================ 换算

  /**
   * 换算：把原价换到目标币，**带舍入**。
   *
   * <p>这是本模块**唯一**的换算入口：冻结时算一次、复算时算一次，两次走同一段代码。
   * 复算能力的前提就是「两次算的是同一件事」。
   *
   * <p>BASE_TO_QUOTE ⇒ 原价 × 汇率；QUOTE_TO_BASE ⇒ 原价 ÷ 汇率
   * （反向汇率由正向推导，不另存一条 —— 见 V225 第二节）。
   */
  public static BigDecimal convert(
      BigDecimal sourceAmount,
      BigDecimal rate,
      ConversionDirection direction,
      int scale,
      RoundingMode mode) {
    if (sourceAmount == null || rate == null || direction == null) return null;
    if (direction == ConversionDirection.BASE_TO_QUOTE) {
      return sourceAmount.multiply(rate).setScale(scale, mode);
    }
    return sourceAmount.divide(rate, scale, mode);
  }

  /**
   * 换算过程的整句显示：「100.0 USD × 7.1234 = 712.34 CNY（HALF_UP · 2 位小数）」。
   *
   * <p>页面上显示的是这一整句，**不**显示裸数字 —— 换算过程必须能被一眼读懂，
   * 否则「这个 712.34 是怎么来的」就答不出来。
   */
  public static String conversionDisplay(
      BigDecimal sourceAmount,
      String sourceCurrency,
      BigDecimal rate,
      String targetCurrency,
      ConversionDirection direction,
      int scale,
      RoundingMode mode) {
    if (sourceAmount == null || rate == null || direction == null) return "—";
    var target = convert(sourceAmount, rate, direction, scale, mode);
    var operator = direction == ConversionDirection.BASE_TO_QUOTE ? "×" : "÷";
    return amountText(sourceAmount)
        + " "
        + sourceCurrency
        + " "
        + operator
        + " "
        + ExchangeRateRules.plain(rate)
        + " = "
        + amountText(target)
        + " "
        + targetCurrency
        + "（"
        + (mode == null ? "?" : mode.name())
        + " · "
        + scale
        + " 位小数）";
  }

  /**
   * 金额的显示串：去掉无意义的尾零，但**至少保留一位小数**
   * （{@code 100.000000} → {@code 100.0}，不显示成 {@code 100}）。
   */
  public static String amountText(BigDecimal value) {
    if (value == null) return "—";
    var stripped = value.stripTrailingZeros();
    if (stripped.scale() < 1) stripped = stripped.setScale(1);
    return stripped.toPlainString();
  }

  // ================================================================ 写入校验

  /**
   * 冻结一条快照的应用层校验（返回**全部**问题，不是一次只报一个）。
   *
   * <p>真正的约束在数据库（V229 的 CHECK / UNIQUE / 触发器），这里是为了早点把话说清楚。
   *
   * @param input 冻结请求
   * @param rateBase 已解析出的汇率基准币（null = 还没解析出汇率）
   * @param rateQuote 已解析出的汇率报价币
   */
  public static List<String> validateSnapshot(
      SnapshotInput input, String rateBase, String rateQuote) {
    var problems = new ArrayList<String>();
    if (input == null) {
      problems.add("冻结内容不能为空");
      return problems;
    }
    var hasRateId = input.rateId() != null;
    var hasPair =
        notBlank(input.baseCurrency()) && notBlank(input.quoteCurrency());
    if (!hasRateId && !hasPair) {
      problems.add("必须指定要冻结的汇率：给 rateId，或给 baseCurrency + quoteCurrency（按 asOf 取当天或之前最新的一条）");
    }
    if (hasPair) {
      var base = ExchangeRateRules.normalizeCurrency(input.baseCurrency());
      var quote = ExchangeRateRules.normalizeCurrency(input.quoteCurrency());
      if (base == null || quote == null) {
        problems.add("币种对必须是三位大写币种代码");
      } else if (base.equals(quote)) {
        problems.add("本币对本币不需要汇率");
      }
    }
    var sourceProblem = ExchangeRateRules.currencyProblem(input.sourceCurrency());
    if (sourceProblem != null) problems.add("原价币种：" + sourceProblem);
    if (input.sourceAmount() == null) {
      problems.add("原价不能为空");
    } else if (input.sourceAmount().scale() > AMOUNT_SCALE) {
      problems.add("原价最多保留 " + AMOUNT_SCALE + " 位小数（数据库列是 numeric(19,6)）");
    }
    var documentType = DocumentType.parse(input.documentType());
    if (documentType == null) {
      problems.add("单据类型不合法：只能是 PRICE_LIST / PRICE_LIST_ITEM / PRICING_TEMPLATE / PROFIT_CALCULATION / ORDER / SETTLEMENT");
    }
    if (input.documentId() == null) problems.add("单据 id 不能为空：快照必须挂在某张单据上");
    if (notBlank(input.lineKey()) && input.lineKey().trim().length() > 100) {
      problems.add("单据行标识最长 100 个字符");
    }
    if (rateBase != null && rateQuote != null) {
      var direction = resolveDirection(rateBase, rateQuote, input.sourceCurrency());
      if (direction == null) {
        problems.add(directionProblem(rateBase, rateQuote, input.sourceCurrency()));
      }
    }
    return problems;
  }

  /** 舍入规则配置的校验。 */
  public static List<String> validateRoundingRule(
      String code, String name, String targetCurrency, String roundingMode, Integer scale) {
    var problems = new ArrayList<String>();
    if (!notBlank(code)) {
      problems.add("规则代码不能为空");
    } else if (!code.trim().matches("[A-Za-z0-9_]{2,32}")) {
      problems.add("规则代码请使用 2~32 位字母、数字或下划线");
    }
    if (!notBlank(name)) problems.add("规则名称不能为空");
    if (notBlank(targetCurrency)) {
      var currency = ExchangeRateRules.normalizeCurrency(targetCurrency);
      if (currency == null || !ExchangeRateRules.validCurrency(currency)) {
        problems.add("适用币种请用三位大写币种代码；通用规则请留空");
      }
    }
    if (RoundingModeOption.parse(roundingMode) == null) {
      problems.add("舍入方式只能是 HALF_UP / HALF_DOWN / DOWN / UP / CEILING / FLOOR");
    }
    if (scale == null) {
      problems.add("小数位数不能为空");
    } else if (scale < 0 || scale > AMOUNT_SCALE) {
      problems.add("小数位数必须在 0~" + AMOUNT_SCALE + " 之间（金额列是 numeric(19,6)）");
    }
    return problems;
  }

  /** 去掉首尾空白后的值（空串返回 null）。 */
  public static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  // ================================================================ 能力声明文案

  /** 方向规则的完整说明（页面上原样显示，不允许前端自己改写）。 */
  public static final String DIRECTION_RULE =
      "汇率方向由三个字段唯一确定：冻结的汇率（基准币 → 报价币）、原价站在哪一侧（conversionDirection）、"
          + "以及实际换算的两侧（原价币 → 目标币）。"
          + "例如冻结的是「1 USD = 7.1234 CNY」而原价币是 CNY，那就是「除」：100 CNY ÷ 7.1234 = 14.04 USD。"
          + "页面上任何一处显示汇率都带完整方向，不会出现裸数字。";

  /** 冻结与只追加的规则说明。 */
  public static final String IMMUTABILITY_RULE =
      "快照是**冻结**的历史事实：汇率、方向、生效日、来源、舍入方式、目标币金额全部整行冻结，"
          + "读快照不 JOIN 汇率表取值 —— 今天汇率改了、来源被停用了，历史快照一个数字都不动。"
          + "快照只追加、不允许修改：同一 单据行 + 同一 币种对 只能有一条，"
          + "要更正就换单据行标识另冻一条，原快照原样保留。";

  /** 舍入规则的解析顺序说明。 */
  public static final String ROUNDING_RULE =
      "舍入规则是租户级配置（模式 + 小数位 + 适用币种）。解析顺序是**先具体后通用**："
          + "先找适用币种正好等于目标币种的规则，找不到再找通用兜底规则（适用币种为空的那条）。"
          + "冻结时把用到的规则代码、模式与小数位一起冻进快照 —— 之后改规则不动任何历史快照。";

  /** 上游关系说明。 */
  public static final String UPSTREAM_NOTE =
      "上游是 5.2.2.1 汇率查询：快照只能冻结**已登记**的汇率事实（rate_id 必填）。"
          + "想冻结一条还没登记的汇率，先在 5.2.2.1 登记它 —— 这样每一条快照的汇率都有出处。";

  /** 下游关系说明。 */
  public static final String DOWNSTREAM_NOTE =
      "下游是 09 订单、12 财务、13 利润：它们读快照复算利润，而不是自己再取一次汇率。"
          + "本模块只冻结与复算，不代下游做账，也不替 13 利润出报表。";

  /** 数据归属说明。 */
  public static final String OWNERSHIP_NOTE =
      "快照表与舍入规则表都归 catalog 服务（卷 05「定价与价目」的主责服务），"
          + "不跨服务读写、不建跨库外键：09 订单与 12 财务的单据不在本库，只用逻辑引用 + 单据号定位。";

  /** 复算能力的约定说明。 */
  public static final String REPLAY_CONTRACT_NOTE =
      "复算 = 拿快照里冻结的原价、汇率、方向、舍入方式重新算一遍目标币金额，"
          + "与冻结时写下的目标币金额逐笔比对。一致说明这批历史利润仍然可复算；"
          + "不一致说明有人动过数据（快照本身受数据库触发器保护，正常情况下不会出现）。";
}
