package com.starcon.catalog;

import com.starcon.catalog.ExchangeRateModels.EntryMode;
import com.starcon.catalog.ExchangeRateModels.RateInput;
import com.starcon.catalog.ExchangeRateModels.SourceKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 5.2.2.1 汇率查询的**纯计算**规则（无 IO，可单测）。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **汇率方向必须显式**（1 USD = X CNY 与 1 CNY = X USD 混淆是常见错源）。
 *
 * </blockquote>
 *
 * <p>⇒ 这一条在代码里有**三个**落点，少一个都不算数：
 *
 * <ol>
 *   <li><b>存储层</b>：{@code exchange_rate} 同时存 {@code base_currency} 与 {@code quote_currency}，
 *       换算方向由这两个字段唯一确定（见 V225 迁移第二节）。缺一个字段的裸数字写不进去。
 *   <li><b>显示层</b>：{@link #display} 产出整句「1 USD = 7.1234 CNY」，
 *       {@link #inverseDisplay} 产出「1 CNY = 0.1404 USD（由 1 USD = 7.1234 CNY 推导）」。
 *       页面与接口都直接用这两句，**不允许**把裸的 {@code rate} 单独显示 —— 裸数字加上
 *       「汇率：7.1234」这种文案，正是 1 USD 与 1 CNY 混淆的来源。
 *   <li><b>写入层</b>：{@link #validateManual} 与 DB 触发器一起挡住「手工再录一条方向相反的」。
 *       反向汇率只允许**由正向推导**（{@link #inverseRate}），推���值带标注、不落库。
 * </ol>
 *
 * <h2>🔴 金额与汇率一律 BigDecimal，禁浮点</h2>
 *
 * <p>AGENTS.md：禁止浮点记账。汇率是本仓精度要求最高的数字（DB 层 {@code numeric(19,8)}），
 * 所以这里的每一次除法都带 scale 与舍入模式：{@link #RATE_SCALE} 位、{@link RoundingMode#HALF_UP}。
 */
public final class ExchangeRateRules {

  /** 汇率精度：8 位小数（与 V225 的 {@code numeric(19,8)} 一致）。 */
  public static final int RATE_SCALE = 8;

  /** 舍入模式：四舍五入（HALF_UP）。与本仓其它金额计算保持一致。 */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** ISO 4217 三位大写码。 */
  private static final String CURRENCY = "[A-Z]{3}";

  private ExchangeRateRules() {}

  // ================================================================ 币种

  /**
   * 归一化币种码：去空白 + 转大写。
   *
   * <p>用 {@code varchar(3)} 而不是 {@code char(3)}：char 会补空格，'USD ' 与 'USD' 不相等。
   */
  public static String normalizeCurrency(String raw) {
    return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
  }

  /** 币种码是否合法。 */
  public static boolean validCurrency(String raw) {
    var normalized = normalizeCurrency(raw);
    return normalized != null && normalized.matches(CURRENCY);
  }

  /** 币种码的问题描述（合法时返回 null）。 */
  public static String currencyProblem(String raw) {
    if (raw == null || raw.trim().isEmpty()) return "币种不能为空";
    if (!validCurrency(raw)) return "币种请用三位大写币种代码（如 USD / CNY）";
    return null;
  }

  // ================================================================ 方向显示

  /**
   * 正向汇率的整句显示：「1 USD = 7.1234 CNY」。
   *
   * <p>小数位去掉无意义的尾零（DB 里是定标 8 位的 numeric，{@code 7.00000000} 显示成
   * {@code 7} 更像人话），但**至少保留一位小数**（{@code 7.0}），免得读成整数汇率。
   */
  public static String display(String baseCurrency, String quoteCurrency, BigDecimal rate) {
    if (baseCurrency == null || quoteCurrency == null || rate == null) return "—";
    return "1 " + baseCurrency + " = " + plain(rate) + " " + quoteCurrency;
  }

  /**
   * 反向汇率（由正向推导）：1 quote = 1/rate base。
   *
   * <p><b>🔴 这是推导值，不是独立事实</b>：它永远不落库（落库就成了「两个互相矛盾的事实」，
   * 见 V225 迁移第二节的反向守卫触发器）。返回值带 8 位小数精度，不做二次舍入。
   */
  public static BigDecimal inverseRate(BigDecimal rate) {
    if (rate == null || rate.signum() == 0) return null;
    return BigDecimal.ONE.divide(rate, RATE_SCALE, ROUNDING);
  }

  /**
   * 反向汇率的整句显示：「1 CNY = 0.14038429 USD（由 1 USD = 7.1234 CNY 推导）」。
   *
   * <p>括号里的来源句是**硬要求**：页面上必须让人看出这个数是从哪个方向推出来的，
   * 否则「0.14038429」又是一枚裸数字。
   */
  public static String inverseDisplay(String baseCurrency, String quoteCurrency, BigDecimal rate) {
    var inverse = inverseRate(rate);
    if (inverse == null) return "—";
    return "1 "
        + quoteCurrency
        + " = "
        + plain(inverse)
        + " "
        + baseCurrency
        + "（由 "
        + display(baseCurrency, quoteCurrency, rate)
        + " 推导）";
  }

  /**
   * 去掉尾零的小数串。
   *
   * <p>至少保留一位小数：{@code 7.00000000} → {@code 7.0}，不显示成 {@code 7}
   * （整数汇率容易被误读成「7 倍」而不是「7.0 汇率」）。
   */
  public static String plain(BigDecimal value) {
    if (value == null) return "—";
    var stripped = value.stripTrailingZeros();
    if (stripped.scale() < 1) stripped = stripped.setScale(1);
    return stripped.toPlainString();
  }

  // ================================================================ 写入校验

  /**
   * 手工录入的校验（应用层；DB 层还有唯一索引与两个守卫触发器兜底）。
   *
   * <p>返回**全部**问题而不是第一个：一次把话说清楚，运营不用来回试。
   *
   * @param input 录入内容
   * @param sourceKind 已登记来源的类型（null = 来源未登记）
   * @param sourceEnabled 已登记来源是否启用（null = 来源未登记）
   */
  public static List<String> validateManual(RateInput input, SourceKind sourceKind, Boolean sourceEnabled) {
    var problems = new ArrayList<String>();
    if (input == null) {
      problems.add("录入内容不能为空");
      return problems;
    }
    var baseProblem = currencyProblem(input.baseCurrency());
    if (baseProblem != null) problems.add("基准币种：" + baseProblem);
    var quoteProblem = currencyProblem(input.quoteCurrency());
    if (quoteProblem != null) problems.add("报价币种：" + quoteProblem);
    if (baseProblem == null && quoteProblem == null) {
      var base = normalizeCurrency(input.baseCurrency());
      var quote = normalizeCurrency(input.quoteCurrency());
      if (base.equals(quote)) problems.add("本币对本币不需要汇率");
    }
    if (input.rate() == null) {
      problems.add("汇率不能为空");
    } else if (input.rate().signum() <= 0) {
      problems.add("汇率必须大于 0");
    } else if (input.rate().scale() > RATE_SCALE) {
      problems.add("汇率最多保留 " + RATE_SCALE + " 位小数");
    }
    if (input.effectiveDate() == null) {
      problems.add("生效日期不能为空");
    }
    if (input.sourceCode() == null || input.sourceCode().trim().isEmpty()) {
      problems.add("汇率来源不能为空：请先在汇率来源配置里登记来源");
    } else if (sourceKind == null) {
      problems.add("汇率来源「" + input.sourceCode().trim() + "」未登记，请先在汇率来源配置里登记该来源");
    } else if (sourceEnabled != null && !sourceEnabled) {
      problems.add("汇率来源「" + input.sourceCode().trim() + "」已停用：停用只挡住「再用它录入」，已登记的汇率仍可读");
    }
    return problems;
  }

  /**
   * 录入方式与来源类型是否一致。
   *
   * <p>来源是手工（MANUAL）却把这条标成外部拉取（EXTERNAL），就是在编造来源 ——
   * DB 触发器 {@code guard_exchange_rate_source} 也钉了同一条，两个层次都要有。
   */
  public static boolean entryModeMatches(SourceKind kind, EntryMode mode) {
    if (kind == null || mode == null) return false;
    return kind.name().equals(mode.name());
  }

  /** 录入方式与来源类型不符时的说明。 */
  public static String entryModeProblem(SourceKind kind, EntryMode mode) {
    if (kind == null) return "来源未登记";
    if (!entryModeMatches(kind, mode)) {
      return "录入方式与来源类型不符：来源是「" + kind.label() + "」，不能登记为「" + mode.label() + "」";
    }
    return null;
  }

  // ================================================================ 词表文案

  /** 来源类型的中文名。 */
  public static String sourceKindLabel(String raw) {
    var kind = SourceKind.parse(raw);
    return kind == null ? (raw == null || raw.isBlank() ? "未登记" : raw) : kind.label();
  }

  /** 录入方式的中文名。 */
  public static String entryModeLabel(String raw) {
    var mode = EntryMode.parse(raw);
    return mode == null ? (raw == null || raw.isBlank() ? "未登记" : raw) : mode.label();
  }

  // ================================================================ 能力声明文案

  /** 方向规则的完整说明（页面上原样显示，不允许前端自己改写）。 */
  public static final String DIRECTION_RULE =
      "汇率方向由「基准币种 → 报价币种」两个字段唯一确定：记录里的 rate 表示 1 基准币种 = rate 报价币种，"
          + "例如 base=USD、quote=CNY、rate=7.1234 就是「1 USD = 7.1234 CNY」。"
          + "页面上任何一处显示汇率都带完整方向，不会出现裸数字。";

  /** 唯一性规则说明。 */
  public static final String UNIQUENESS_RULE =
      "同一 币种对 + 同一生效日 只能有一条生效记录 —— 否则「今天的汇率」有多个答案，"
          + "5.2.2.2 汇率快照与 5.3 测算不知道该冻结哪一条。重复录入会被数据库唯一约束挡下。";

  /** 反向汇率处理说明。 */
  public static final String INVERSE_RULE =
      "反向汇率（1 报价币种 = ? 基准币种）由正向汇率**显式推导**并标注为推导值，不落库；"
          + "手工再录一条方向相反的记录会被数据库守卫触发器拒绝 —— 那是两个互相矛盾的事实。"
          + "要换方向请改正向那条。";

  /** 下游状态说明（如实写明未交付）。 */
  public static final String DOWNSTREAM_NOTE =
      "下游 5.2.2.2 汇率快照**尚未交付**：它上游是本模块，冻结单据/定价生效时所用的汇率。"
          + "本模块只产出**汇率事实**，不假装快照已存在。5.3 定价测算与 12 财务同样是下游，本模块不代它们冻结汇率。";

  /** 数据归属说明。 */
  public static final String OWNERSHIP_NOTE =
      "汇率表归 catalog 服务（卷 05 定价与价目的主责服务）。12 财务（finance 服务）当前不存在，"
          + "本模块不建 finance 侧的表、不读写 finance，也不 ALTER 5.2.1.1 成本构成的表结构。";

  /** 本位币来源说明。 */
  public static final String FUNCTIONAL_CURRENCY_NOTE =
      "本位币归**法人**（17.1.2.2，identity 服务），租户默认币种只是展示默认，两者不能混。"
          + "本模块经 identity 的授权只读端点读取法人的本位币，读取失败时如实报「读不到」，"
          + "不用空集合冒充「没有本位币」。";

  /** 外部拉取的响应格式约定。 */
  public static final String FETCH_CONTRACT_NOTE =
      "外部拉取会真的向来源配置的 endpoint 发起 HTTP GET（查询参数 base / quote / date），"
          + "期望返回 JSON：{\"rate\":\"7.1234\",\"effectiveDate\":\"2026-10-20\",\"sourceRef\":\"...\"} "
          + "或 {\"rates\":[...]} 数组形式。拉取失败（连不上 / 非 2xx / 响应不认）会留失败日志与具体原因，"
          + "不会静默返回空。";
}
