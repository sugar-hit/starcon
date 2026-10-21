package com.starcon.trade;

import static com.starcon.trade.DevelopmentProjectModels.DevelopmentStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.6.1.1 开发立项的纯算式与状态机（不碰数据库，可单测）。
 *
 * <p><b>为什么单独一个类</b>：本模块最容易出错的两件事是「状态迁移合不合法」与「毛利率算得对不对」。
 * 这两件事一旦混进 jOOQ 的 SQL 里，读代码的人就看不出「为什么这个立项不能直接跳到量产」，
 * 也看不出「毛利率为什么是 25.3456% 而不是 25.35%」——
 * 而这正是蓝图加粗句「把开发-打样-试产-量产纳入同一编号体系」要防的分叉。
 *
 * <p><b>五条纪律</b>：
 *
 * <ol>
 *   <li><b>状态机是单向的</b>：{@code EVALUATION → APPROVED → IN_DEVELOPMENT → SAMPLING
 *       → PILOT → MASS_PRODUCTION}，外加任意非终态 → {@code TERMINATED}。
 *       {@code TERMINATED} 是**终态**，没有出边，**不提供「恢复」** ——
 *       「恢复」会让「这个立项为什么停了」永远查不回来，而那正是终止事件存在的理由。</li>
 *   <li><b>跳过打样 / 试产必须留豁免记录</b>：正常路径必须逐级走。
 *       要跳，必须给出豁免码 + 豁免说明（成对，缺一不算豁免）。
 *       「悄悄跳过去」与「明确豁免」是两件事 —— 后者会留在迁移事件里可被追问。</li>
 *   <li><b>目标毛利率一律服务端算</b>：{@code 毛利 = (目标售价 − 目标成本) ÷ 目标售价}，
 *       <b>单次</b> HALF_UP 舍入到 {@link #MARGIN_RATE_SCALE} 位小数。
 *       调用方**不许**自己报一个毛利率 —— 客户端报上来的毛利率是「愿望」，不是结论。</li>
 *   <li><b>舍入口径全局唯一</b>：{@link #MARGIN_RATE_SCALE} 位 HALF_UP，与 5.3.1.1 利润测算器的
 *       毛利率口径**一致**（6 位）。口径不一致会让同一个立项在定价页与立项页显示两个毛利，
 *       而「这两个数哪个对」是没人答得出来的问题。
 *       <b>百分比的两位小数只是显示格式</b>（{@code 费率 × 100} 再舍入），
 *       它是同一个 canonical 值的格式化结果，**不是第二次计算**。</li>
 *   <li><b>成本取不到就是取不到</b>：{@code targetCost} 为 null ⇒ 毛利率为 null，
 *       <b>绝不</b>用 0 顶替（成本 0 的毛利率是 100%，是彻头彻尾的假数据）。</li>
 * </ol>
 */
public final class DevelopmentProjectRules {

  private DevelopmentProjectRules() {}

  /**
   * 目标毛利率的刻度：<b>6 位小数 HALF_UP</b>。
   *
   * <p>🔴 与 5.3.1.1 利润测算器（{@code ProfitCalculatorRules}）的毛利率刻度**逐字一致** ——
   * 同一个立项在定价页与立项页显示两个毛利，是「这两个数哪个对」这种没人答得出来的问题。
   * 小数口径：{@code 0.253456} = 25.3456%。
   */
  public static final int MARGIN_RATE_SCALE = 6;

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  public static final int MONEY_SCALE = 4;

  /** 目标售价必须大于 0 —— 售价为 0 时毛利率无定义（除零）。 */
  public static void requirePositivePrice(BigDecimal price) {
    if (price == null || price.signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "目标售价必须大于 0（售价为 0 时毛利率无定义）");
    }
  }

  /** 目标成本不得为负。 */
  public static void requireNonNegativeCost(BigDecimal cost) {
    if (cost != null && cost.signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标成本不能为负");
    }
  }

  /**
   * 目标毛利率。
   *
   * <p><b>公式</b>：{@code 毛利 = (目标售价 − 目标成本) ÷ 目标售价}，
   * <b>单次</b> {@link RoundingMode#HALF_UP} 舍入到 {@link #MARGIN_RATE_SCALE} 位小数。
   *
   * <p>🔴 「单次」是关键：先按高精度除一次再舍入到 6 位，与「先舍入到 2 位再除」会得到不同的数。
   * 本仓**只有这一处**做这个除法（服务层与前端都读它落库的结论，不各自复算）。
   *
   * @param targetPrice 目标售价（必须 &gt; 0）
   * @param targetCost 目标成本（非负）
   */
  public static BigDecimal targetMarginRate(BigDecimal targetPrice, BigDecimal targetCost) {
    requirePositivePrice(targetPrice);
    requireNonNegativeCost(targetCost);
    if (targetCost == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "目标成本取不到时不能算毛利率 —— 成本 0 会得到 100% 的假毛利");
    }
    return targetPrice
        .subtract(targetCost)
        .divide(targetPrice, MARGIN_RATE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 毛利率算式口径原文 —— 随结论一起返回。
   *
   * <p>「这个毛利率是怎么算出来的」不靠复算，也不靠读代码：写在这一行里。
   */
  public static String marginBasis(BigDecimal targetPrice, BigDecimal targetCost, String currency) {
    if (targetCost == null) {
      return "毛利 = (目标售价 − 目标成本) ÷ 目标售价；目标成本取不到，本立项**不输出目标毛利率**"
          + "（绝不用 0 顶替成本 —— 成本 0 的毛利率是 100%，是假数据）";
    }
    return "毛利 = (目标售价 − 目标成本) ÷ 目标售价 = ("
        + targetPrice.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        + " − "
        + targetCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        + ") ÷ "
        + targetPrice.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        + " = "
        + targetMarginRate(targetPrice, targetCost).toPlainString()
        + "（"
        + currency
        + "；单次 HALF_UP 舍入到 "
        + MARGIN_RATE_SCALE
        + " 位小数，与 5.3.1.1 利润测算器口径一致）";
  }

  /** 成本取不到时的毛利口径原文。 */
  public static String unavailableMarginBasis(String ownerModule, String reason) {
    return "毛利 = (目标售价 − 目标成本) ÷ 目标售价；目标成本**取不到**（归 "
        + ownerModule
        + " 管："
        + reason
        + "）⇒ 本立项**不输出目标毛利率**（绝不用 0 顶替成本）";
  }

  // ================================================================ 状态机

  /**
   * 从某个状态**允许**迁到哪些状态。
   *
   * <p>与 V257 迁移里 {@code guard_development_project_status()} 的 CASE 分支**逐条同构** ——
   * 服务层先挡一道（给出可读的 400），数据库层再挡一道（挡住绕过服务的写入）。
   */
  public static Set<DevelopmentStatus> allowedTargets(DevelopmentStatus from) {
    return switch (from) {
      case EVALUATION -> ordered(DevelopmentStatus.APPROVED, DevelopmentStatus.TERMINATED);
      case APPROVED ->
          ordered(DevelopmentStatus.IN_DEVELOPMENT, DevelopmentStatus.TERMINATED);
      case IN_DEVELOPMENT ->
          ordered(
              DevelopmentStatus.SAMPLING,
              DevelopmentStatus.PILOT,
              DevelopmentStatus.MASS_PRODUCTION,
              DevelopmentStatus.TERMINATED);
      case SAMPLING ->
          ordered(
              DevelopmentStatus.PILOT,
              DevelopmentStatus.MASS_PRODUCTION,
              DevelopmentStatus.TERMINATED);
      case PILOT -> ordered(DevelopmentStatus.MASS_PRODUCTION, DevelopmentStatus.TERMINATED);
      // 🔴 终止是终态：没有出边，不提供「恢复」。
      case MASS_PRODUCTION -> ordered(DevelopmentStatus.TERMINATED);
      case TERMINATED -> ordered();
    };
  }

  /**
   * 这次迁移是否**跳过了打样 / 试产**（因而必须有显式豁免记录）。
   *
   * <p>只有三条跳跃需要豁免：
   * {@code IN_DEVELOPMENT → PILOT}（跳过打样）、
   * {@code IN_DEVELOPMENT → MASS_PRODUCTION}（跳过打样与试产）、
   * {@code SAMPLING → MASS_PRODUCTION}（跳过试产）。
   */
  public static boolean requiresExemption(DevelopmentStatus from, DevelopmentStatus to) {
    return (from == DevelopmentStatus.IN_DEVELOPMENT
            && (to == DevelopmentStatus.PILOT || to == DevelopmentStatus.MASS_PRODUCTION))
        || (from == DevelopmentStatus.SAMPLING && to == DevelopmentStatus.MASS_PRODUCTION);
  }

  /**
   * 校验一次状态迁移；不合法就 400。
   *
   * @param exemptionCode 豁免码（跳过打样 / 试产时必填）
   * @param exemptionNote 豁免说明（跳过打样 / 试产时必填）
   */
  public static void requireTransition(
      DevelopmentStatus from,
      DevelopmentStatus to,
      String exemptionCode,
      String exemptionNote) {
    if (from == to) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "目标状态与当前状态相同（" + from.label() + "），这不是一次状态迁移");
    }
    Set<DevelopmentStatus> allowed = allowedTargets(from);
    if (!allowed.contains(to)) {
      String targets =
          allowed.isEmpty()
              ? "无（终止是终态，不提供恢复）"
              : allowed.stream().map(DevelopmentStatus::label).toList().toString();
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "开发立项不能从「"
              + from.label()
              + "」迁到「"
              + to.label()
              + "」。允许的目标状态："
              + targets);
    }
    if (requiresExemption(from, to)) {
      if (exemptionCode == null || exemptionCode.isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "从「"
                + from.label()
                + "」跳到「"
                + to.label()
                + "」跳过了打样 / 试产，必须给出显式的豁免记录（豁免码 + 豁免说明）");
      }
      if (exemptionNote == null || exemptionNote.isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "跳过了打样 / 试产，豁免说明必填：只给一个豁免码不构成可追问的豁免记录");
      }
    }
  }

  /** 状态是否终态。 */
  public static boolean isTerminal(DevelopmentStatus status) {
    return status == DevelopmentStatus.TERMINATED;
  }

  // ================================================================ 杂项

  /** 币种归一：三位大写字母。 */
  public static String normalizeCurrency(String currency) {
    if (currency == null || currency.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必填（ISO 4217 三位大写字母）");
    }
    String normalized = currency.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "币种必须是三位大写字母，收到的是 " + currency);
    }
    return normalized;
  }

  /** 保留顺序的不可变集合。 */
  private static Set<DevelopmentStatus> ordered(DevelopmentStatus... values) {
    return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
  }
}
