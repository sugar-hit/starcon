package com.starcon.trade;

import static com.starcon.trade.ProductionTrialModels.ProductionTrialJudgment;
import static com.starcon.trade.ProductionTrialModels.ProductionTrialMassProductionDecision;
import static com.starcon.trade.ProductionTrialModels.ProductionTrialStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.6.2.1 试产单的纯规则（不碰数据库，可单测）。
 *
 * <p><b>为什么单独一个类</b>：本模块最容易出错的三件事是「状态迁移合不合法」、
 * 「良率 / 成本偏差怎么算」与「转量产建议的结论有没有举证」。这三件事一旦混进 jOOQ 的 SQL 里，
 * 读代码的人就看不出「为什么这个试产单不能跳过排产直接开产」，
 * 也看不出「为什么良率 0.95 而不是 95」——
 * 而这正是蓝图加粗句「试产消耗真实组件库存，需走 8 加工单而非直接改库存」与
 * 「试产成本与良率」要防的分叉。
 *
 * <p><b>七条纪律</b>：
 *
 * <ol>
 *   <li><b>试产单状态机是单向的</b>：{@code REQUESTED → SCHEDULED → IN_PROGRESS → COMPLETED → CLOSED}，
 *       外加任意非终态 → {@code CANCELLED}。{@code CLOSED} / {@code CANCELLED} 是<b>终态</b>，
 *       没有出边，<b>不提供「恢复」</b>。</li>
 *   <li><b>良率是比例</b>：{@code 合格 ÷ 产出}，单次 HALF_UP 到 <b>6 位小数</b>。
 *       1 000 产出 950 合格 ⇒ 良率 0.95（**不是 95**）。</li>
 *   <li><b>成本偏差率是比例</b>：{@code (试产成本 − 量产目标成本) ÷ 量产目标成本}，
 *       单次 HALF_UP 到 <b>6 位小数</b>。量产目标成本取不到 ⇒ 偏差率取不到，
 *       <b>不按 1:1 折算、不拿 0 顶替</b>。</li>
 *   <li><b>「建议转量产」需要举证</b>：{@code GO} 必须同时满足良率门槛与成本门槛；
 *       {@code NO_GO} 不需要举证（关键质检项不合格也足以否掉量产）——
 *       <b>可以放行「建议」，不能放行「没证据的建议」</b>。</li>
 *   <li><b>金额必须 &gt; 0</b>（一条 0 元的成本明细不携带任何信息），
 *       数量必须 &gt; 0，币种三位大写字母。<b>禁浮点</b>：全链路 BigDecimal。</li>
 *   <li><b>一张试产单一个币种</b>：明细行币种必须与成本头一致，不同币种的数绝不相加。</li>
 *   <li><b>判定不可撤回成「待判定」</b>：质检项一旦判过就是判过了 ——
 *       撤回等于把「当时判过不合格」这段证据抹掉。</li>
 * </ol>
 */
public final class ProductionTrialRules {

  private ProductionTrialRules() {}

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  public static final int MONEY_SCALE = 4;

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  public static final int QUANTITY_SCALE = 6;

  /** 良率 / 成本偏差率的刻度：比例，同样是 numeric(19,6)。 */
  public static final int RATE_SCALE = 6;

  // ================================================================ 试产单状态机

  /**
   * 试产单从某个状态<b>允许</b>迁到哪些状态。
   *
   * <p>与 V264 迁移里 {@code guard_production_trial_order_status()} 的 CASE 分支<b>逐条同构</b> ——
   * 服务层先挡一道（给出可读的 400），数据库层再挡一道（挡住绕过服务的写入）。
   */
  public static Set<ProductionTrialStatus> allowedTargets(ProductionTrialStatus from) {
    return switch (from) {
      case REQUESTED -> ordered(ProductionTrialStatus.SCHEDULED, ProductionTrialStatus.CANCELLED);
      case SCHEDULED -> ordered(ProductionTrialStatus.IN_PROGRESS, ProductionTrialStatus.CANCELLED);
      case IN_PROGRESS -> ordered(ProductionTrialStatus.COMPLETED, ProductionTrialStatus.CANCELLED);
      case COMPLETED -> ordered(ProductionTrialStatus.CLOSED);
      // 🔴 已关闭 / 已取消是终态：没有出边，不提供「恢复」。
      case CLOSED -> ordered();
      case CANCELLED -> ordered();
    };
  }

  /** 试产单状态是否终态。 */
  public static boolean isTerminalStatus(ProductionTrialStatus status) {
    return status == ProductionTrialStatus.CLOSED || status == ProductionTrialStatus.CANCELLED;
  }

  /** 校验一次试产单状态迁移；不合法就 400。 */
  public static void requireTransition(ProductionTrialStatus from, ProductionTrialStatus to) {
    if (from == to) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "目标状态与当前状态相同（" + from.label() + "），这不是一次状态迁移");
    }
    Set<ProductionTrialStatus> allowed = allowedTargets(from);
    if (!allowed.contains(to)) {
      String targets =
          allowed.isEmpty()
              ? "无（已关闭 / 已取消是终态，不提供恢复）"
              : allowed.stream().map(ProductionTrialStatus::label).toList().toString();
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "试产单不能从「" + from.label() + "」迁到「" + to.label() + "」。允许的目标状态：" + targets);
    }
  }

  /**
   * 「建议转量产」这件事只能在<b>试产已经完成</b>之后发生。
   *
   * <p>没做完的试产没有结果、没有良率、没有成本对比 —— 此时给建议等于凭空捏造。
   */
  public static void requireRecommendationStage(ProductionTrialStatus status) {
    if (status != ProductionTrialStatus.COMPLETED && status != ProductionTrialStatus.CLOSED) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "只有已完成的试产单才能出转量产建议（当前是「" + status.label() + "」）："
              + "建议必须建立在已记录的良率与成本对比之上");
    }
  }

  // ================================================================ 良率与成本偏差

  /**
   * 良率 = 合格 ÷ 产出（**比例**，单次 HALF_UP 到 6 位小数）。
   *
   * <p>与 DB 触发器 {@code guard_production_trial_result()} 的算法<b>逐条同构</b> ——
   * 两处各算一遍必然漂移，而漂移的后果是「界面上显示的良率与库里存的对不上」。
   */
  public static BigDecimal yieldRate(BigDecimal qualifiedQuantity, BigDecimal outputQuantity) {
    if (outputQuantity == null || outputQuantity.signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "试产产出数量必须大于 0（没有产出就没有良率可言）");
    }
    if (qualifiedQuantity == null
        || qualifiedQuantity.signum() < 0
        || qualifiedQuantity.compareTo(outputQuantity) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "试产合格数量必须落在 0 与产出数量之间（产出 " + outputQuantity + "，合格 " + qualifiedQuantity + "）");
    }
    return qualifiedQuantity.divide(outputQuantity, RATE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 成本偏差率 = (试产成本 − 量产目标成本) ÷ 量产目标成本（**比例**，单次 HALF_UP 到 6 位小数）。
   *
   * <p>🔴 <b>量产目标成本取不到就返回 null</b> —— 本服务**不做固定汇率折算**，
   * 也**不拿 0 顶替**。跨币种按币种分组，各组之间不可比。
   */
  public static BigDecimal costVarianceRate(BigDecimal trialCost, BigDecimal massTargetCost) {
    if (massTargetCost == null) {
      return null;
    }
    if (massTargetCost.signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "量产目标成本必须大于 0（拿 0 当分母算出来的偏差率没有意义）");
    }
    if (trialCost == null) {
      return null;
    }
    return trialCost.subtract(massTargetCost).divide(massTargetCost, RATE_SCALE, RoundingMode.HALF_UP);
  }

  /** 成本偏差额 = 试产成本 − 量产目标成本（同一币种内相减）。 */
  public static BigDecimal costVarianceAmount(BigDecimal trialCost, BigDecimal massTargetCost) {
    if (trialCost == null || massTargetCost == null) {
      return null;
    }
    return trialCost.subtract(massTargetCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  // ================================================================ 转量产建议的举证

  /**
   * 「建议转量产」（{@code GO}）必须<b>有举证</b>：良率达标 + 成本门槛达标。
   *
   * <p>🔴 {@code NO_GO}（暂不建议转量产）<b>不需要举证</b> ——
   * 关键质检项不合格也足以否掉量产，那不是良率与成本能表达的。
   * 但反过来：<b>没证据的「建议转量产」必须被挡下</b>。
   *
   * @param observedYieldRate 实测良率（比例）
   * @param minYieldThreshold 良率门槛（比例）
   * @param observedCostVarianceRate 实测成本偏差率（比例）；<b>null = 量产目标成本取不到</b>
   * @param maxCostVarianceRate 成本偏差上限（比例）；<b>null = 本次不设这道门槛</b>
   */
  public static void requireRecommendationEvidence(
      ProductionTrialMassProductionDecision decision,
      BigDecimal observedYieldRate,
      BigDecimal minYieldThreshold,
      BigDecimal observedCostVarianceRate,
      BigDecimal maxCostVarianceRate) {
    if (decision != ProductionTrialMassProductionDecision.GO) {
      // 暂不建议转量产不需要举证 —— 关键质检项不合格也足以否掉量产。
      return;
    }
    boolean yieldOk =
        observedYieldRate != null && observedYieldRate.compareTo(minYieldThreshold) >= 0;
    // 成本门槛：本次不设 = 直接放行；设了但偏差率取不到（量产目标成本不可得）= 不放行。
    boolean costOk;
    if (maxCostVarianceRate == null) {
      costOk = true;
    } else {
      costOk =
          observedCostVarianceRate != null
              && observedCostVarianceRate.abs().compareTo(maxCostVarianceRate) <= 0;
    }
    if (yieldOk && costOk) {
      return;
    }
    StringBuilder why = new StringBuilder("「建议转量产」缺少举证：");
    if (!yieldOk) {
      why.append("实测良率 ")
          .append(observedYieldRate)
          .append(" 未达到门槛 ")
          .append(minYieldThreshold)
          .append("；");
    }
    if (!costOk) {
      if (observedCostVarianceRate == null) {
        why.append("量产目标成本不可得，成本门槛无法评估（不做固定汇率折算）；");
      } else {
        why.append("实测成本偏差率 ")
            .append(observedCostVarianceRate)
            .append(" 超出上限 ")
            .append(maxCostVarianceRate)
            .append("；");
      }
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        why + "结论与生成条件对不上。要么改成「暂不建议转量产」，要么把条件调到与实测相符。");
  }

  // ================================================================ 金额 / 数量 / 币种

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

  /** 成本明细的金额必须 &gt; 0 —— 一条 0 元的明细不携带任何信息。 */
  public static void requirePositiveAmount(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "成本金额必须大于 0（0 元的一条明细不携带任何信息）");
    }
  }

  /** 数量必须 &gt; 0。 */
  public static void requirePositiveQuantity(BigDecimal quantity) {
    if (quantity == null || quantity.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数量必须大于 0");
    }
  }

  /** 比例必须落在 0..1（良率、偏差率、门槛都是比例，不是百分数）。 */
  public static BigDecimal requireRate(BigDecimal rate, String what) {
    if (rate == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + "不能为空");
    }
    if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          what + "是**比例**（0.95 = 95%），必须落在 0 到 1 之间，收到的是 " + rate);
    }
    return rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  /** 明细行的币种必须与成本头一致（一张单一个币种）。 */
  public static void requireSameCurrency(String headCurrency, String lineCurrency) {
    if (headCurrency == null || !headCurrency.equals(lineCurrency)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "成本明细行的币种（" + lineCurrency + "）与成本头（" + headCurrency + "）不一致："
              + "一张试产单一个币种，不同币种的数绝不相加");
    }
  }

  /** 判定不可撤回成「待判定」。 */
  public static void requireJudgment(ProductionTrialJudgment judgment) {
    if (judgment == ProductionTrialJudgment.PENDING) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "判定结果只能是「合格」或「不合格」：撤回成「待判定」等于把「当时判过不合格」这段证据抹掉");
    }
  }

  /** 保留顺序的不可变集合。 */
  private static <T> Set<T> ordered(T... values) {
    return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
  }
}
