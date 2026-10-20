package com.starcon.catalog;

import com.starcon.catalog.PriceAdjustmentModels.Status;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/**
 * 5.4.1.1 调价单的**纯算术与状态机规则**（无 IO、无租户上下文，可单测）。
 *
 * <p>本类只负责四件事：
 *
 * <ol>
 *   <li><b>新价与价差怎么算</b>（{@link #newPrice} / {@link #delta} / {@link #rate}）——
 *       舍入策略钉死在这里，页面与导出都写明同一套；
 *   <li><b>状态之间能不能走</b>（{@link #canTransition} / {@link #transition}）——
 *       转换规则用显式的方式表达，不靠服务层里的 if 串联；
 *   <li><b>归一化</b>（{@link #code} / {@link #trimToNull}）；
 *   <li><b>口径常量</b>（精度）。
 * </ol>
 *
 * <p><b>🔴 纪律一：全程 BigDecimal，零浮点。</b>
 *
 * <p><b>🔴 纪律二：售价 2 位小数，金额 4 位小数，费率 6 位小数。</b>
 *
 * <p>售价（{@link #PRICE_SCALE}）与内部金额（{@link #MONEY_SCALE}）**故意不同**：
 * 售价是**对外标价**，2.1.1.1 平台目录里的 currencyScale 就是 2（一个 listing 卖 12.3456 美元
 * 是没有意义的）；而内部金额要留 4 位小数，因为平台费是售价的**比例**，
 * 4 位小数的扣减项算出来的毛利额要能对得上账。两者都用 HALF_UP，页面上写明。
 *
 * <p><b>🔴 纪律三：状态机是白名单，不是黑名单。</b>
 *
 * <p>没有在 {@link #ALLOWED} 里的转换一律**拒绝**并说清为什么 ——
 * 「已执行的单子不能再执行一次」这件事必须由规则挡住，
 * 而不是靠每个调用点自己记得先查一遍状态。
 */
public final class PriceAdjustmentRules {

  private PriceAdjustmentRules() {}

  /** 售价精度：2 位小数（与 2.1.1.1 平台目录的 currencyScale=2 一致）。 */
  public static final int PRICE_SCALE = 2;

  /** 金额精度：4 位小数（与 5.3.1.1 的 {@code ProfitCalculatorRules.MONEY_SCALE} 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 费率/毛利率精度：6 位小数（与 5.3.1.1 一致）。 */
  public static final int RATE_SCALE = 6;

  /** 舍入方式：四舍五入。全部金额、售价与费率统一用它。 */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** 成本口径窗口的默认跨度（天）。与 5.3.1.1 / 5.3.1.2 / 5.4.1.2 一致。 */
  public static final int DEFAULT_WINDOW_DAYS = 90;

  /** 台账页大小上限：与相邻模块一致。 */
  public static final int MAX_PAGE_SIZE = 200;

  // ================================================================ 状态机

  /**
   * 合法的状态转换白名单。
   *
   * <p>🔴 逐条对应蓝图的输出句与「受控动作」那句：
   *
   * <ul>
   *   <li>{@code DRAFT → PENDING_APPROVAL} —— 提交且命中了审批规则；</li>
   *   <li>{@code DRAFT → APPROVED} —— 提交且**免审**（identity 判 EXEMPT / 未配规则）；
   *       「已批」在这里表示「无需审批即可执行」，决策镜像里会写明 decision=EXEMPT；</li>
   *   <li>{@code DRAFT → REJECTED} —— 本人把这张草稿撤了；</li>
   *   <li>{@code PENDING_APPROVAL → APPROVED / REJECTED} —— 审批结论回来了；</li>
   *   <li>{@code APPROVED → EXECUTED} —— 执行：新价生效并产出待回写事实。</li>
   * </ul>
   *
   * <p>🔴 {@code EXECUTED} 与 {@code REJECTED} 是**终态**：已发生过的调价不许改写，
   * 要再调就**再发一张单**（「这次到底改成了多少」必须事后可追溯）。
   */
  public static final Set<Transition> ALLOWED =
      Set.of(
          new Transition(Status.DRAFT, Status.PENDING_APPROVAL),
          new Transition(Status.DRAFT, Status.APPROVED),
          new Transition(Status.DRAFT, Status.REJECTED),
          new Transition(Status.PENDING_APPROVAL, Status.APPROVED),
          new Transition(Status.PENDING_APPROVAL, Status.REJECTED),
          new Transition(Status.APPROVED, Status.EXECUTED));

  /** 一次状态转换。 */
  public record Transition(Status from, Status to) {}

  /**
   * 这个转换合法吗。
   *
   * <p>纯查询：服务层用它决定是「继续」还是「409 / 400」。
   */
  public static boolean canTransition(Status from, Status to) {
    if (from == null || to == null) return false;
    return ALLOWED.contains(new Transition(from, to));
  }

  /**
   * 校验一次转换；不合法就**明确报错**并说清当前状态与目标状态。
   *
   * <p>🔴 刻意**不**静默兜底成「什么都不做」——那会让调用方以为状态推进成功了。
   *
   * @throws IllegalStateException 转换不在白名单里
   */
  public static void transition(Status from, Status to) {
    if (!canTransition(from, to)) {
      throw new IllegalStateException(
          "调价单状态不能从「"
              + (from == null ? "未知" : from.label())
              + "」走到「"
              + (to == null ? "未知" : to.label())
              + "」—— 合法路径是：草稿 → 待审 → 已批 → 已执行；草稿 / 待审 → 已驳回。"
              + "已执行与已驳回是终态，要再调价请另发一张单。");
    }
  }

  // ================================================================ 价

  /**
   * 新价落定：**2 位小数 HALF_UP**。
   *
   * <p>🔴 返回 {@code null} 表示「这个新价不能用」——调完不是正价。
   * 由调用方**拒绝**并说清原因，**绝不**返回 0：0 会被当成「免费」。
   */
  public static BigDecimal newPrice(BigDecimal raw) {
    if (raw == null) return null;
    BigDecimal rounded = raw.setScale(PRICE_SCALE, ROUNDING);
    return rounded.signum() > 0 ? rounded : null;
  }

  /** 价差（新价 − 旧价）。正 = 涨价，负 = 降价。 */
  public static BigDecimal delta(BigDecimal oldPrice, BigDecimal newPrice) {
    if (oldPrice == null || newPrice == null) return null;
    return money(newPrice.subtract(oldPrice));
  }

  /**
   * 幅度（(新价 − 旧价) / 旧价）。
   *
   * <p>🔴 旧价为 0 时**不可计算** ⇒ 返回 null（**不是 0**）：
   * 「除不出来」与「幅度恰好是 0」是两件事。
   */
  public static BigDecimal rate(BigDecimal oldPrice, BigDecimal newPrice) {
    if (oldPrice == null || newPrice == null) return null;
    if (oldPrice.signum() == 0) return null;
    return rate(newPrice.subtract(oldPrice).divide(oldPrice, 10, ROUNDING));
  }

  /**
   * 调价金额（送审时用的那个数）：**绝对值**的价差。
   *
   * <p>审批规则看的是「这次改了多少」，降价与涨价同等看待。
   */
  public static BigDecimal amount(BigDecimal oldPrice, BigDecimal newPrice) {
    var d = delta(oldPrice, newPrice);
    return d == null ? null : d.abs();
  }

  /** 亏损标记（毛利额 &lt; 0）。毛利额为 null ⇒ 返回 null（不可信），**不是 false**。 */
  public static Boolean lossFlag(BigDecimal grossProfit) {
    if (grossProfit == null) return null;
    return grossProfit.signum() < 0;
  }

  // ================================================================ 口径

  /** 售价：2 位小数，HALF_UP。 */
  public static BigDecimal price(BigDecimal value) {
    return value == null ? null : value.setScale(PRICE_SCALE, ROUNDING);
  }

  /** 金额：4 位小数，HALF_UP。 */
  public static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, ROUNDING);
  }

  /** 费率/毛利率：6 位小数，HALF_UP。 */
  public static BigDecimal rate(BigDecimal value) {
    return value == null ? null : value.setScale(RATE_SCALE, ROUNDING);
  }

  /** 百分比展示（口径串用）：0.25 → 「25%」。 */
  public static String percent(BigDecimal value) {
    if (value == null) return "—";
    return value.multiply(BigDecimal.valueOf(100)).setScale(2, ROUNDING).stripTrailingZeros()
        .toPlainString()
        + "%";
  }

  /** 归一化平台码 / 站点码：去空白 + 大写。空 ⇒ null。 */
  public static String code(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
  }

  /** 去空白；空串 ⇒ null。 */
  public static String trimToNull(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** 去空白；null ⇒ 空串（DB 里的 NOT NULL DEFAULT '' 列用这个）。 */
  public static String trimToEmpty(String raw) {
    if (raw == null) return "";
    return raw.trim();
  }
}
