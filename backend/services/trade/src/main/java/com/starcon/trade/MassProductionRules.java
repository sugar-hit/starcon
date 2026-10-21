package com.starcon.trade;

import static com.starcon.trade.MassProductionModels.MassProductionDecision;
import static com.starcon.trade.MassProductionModels.MassProductionDecisionSource;
import static com.starcon.trade.MassProductionModels.MassProductionFilingStatus;
import static com.starcon.trade.MassProductionModels.MassProductionPlanStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.6.2.2 转量产与建档的纯规则（不碰数据库，可单测）。
 *
 * <p><b>为什么单独一个类</b>：本模块最容易出错的三件事是「状态迁移合不合法」、
 * 「量产决策有没有举证」与「建档请求能不能重试」。这三件事一旦混进 jOOQ 的 SQL 里，
 * 读代码的人就看不出「为什么这个建档请求不许重试」——
 * 而那正是蓝图加粗句「转量产是开发与供应链的交接口，SKU 编码/条码在此时生成」要防的分叉。
 *
 * <p><b>七条纪律</b>：
 *
 * <ol>
 *   <li><b>量产计划状态机是单向的</b>：{@code DRAFT → DECIDED → BOM_FROZEN → SKU_FILED
 *       → RELEASED → CLOSED}，外加任意非终态 → {@code CANCELLED}。
 *       {@code CLOSED} / {@code CANCELLED} 是<b>终态</b>，没有出边，<b>不提供「恢复」</b>。</li>
 *   <li><b>量产决策必须举证</b>：决策理由必填；上游建议是 {@code NO_GO} 而本计划决策是
 *       {@code GO} 时，<b>必须</b>写覆盖理由 —— <b>可以放行「有理由的决策」，
 *       不能放行「没证据的量产」</b>。</li>
 *   <li><b>建档请求的「结果未知」不许重试</b>：catalog 的建档端点<b>不接受幂等键</b>，
 *       超时 / 断连时无法判断它到底建没建 ⇒ 盲目重试可能建出<b>两个</b> SKU。</li>
 *   <li><b>属性值的形状与 catalog 一致</b>：{@code SET} 必须带值，{@code CLEAR} / {@code INHERIT}
 *       必须不带值（继承或清空时附带取值会被 catalog 拒）。</li>
 *   <li><b>金额必须 &gt; 0</b>、数量必须 &gt; 0、币种三位大写字母。<b>禁浮点</b>：全链路 BigDecimal。</li>
 *   <li><b>一个量产计划一个币种</b>：跨币种的数绝不相加（5.2.2.1 汇率查询未交付 ⇒ 不折算）。</li>
 *   <li><b>成本偏差率是比例</b>：{@code (试产实测成本 − 量产目标成本) ÷ 量产目标成本}，
 *       单次 HALF_UP 到 <b>6 位小数</b>。目标成本取不到 ⇒ 偏差率取不到，<b>不拿 0 顶替</b>。</li>
 * </ol>
 */
public final class MassProductionRules {

  private MassProductionRules() {}

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  public static final int MONEY_SCALE = 4;

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  public static final int QUANTITY_SCALE = 6;

  /** 偏差率刻度：比例，同样是 numeric(19,6)。 */
  public static final int RATE_SCALE = 6;

  // ================================================================ 量产计划状态机

  /**
   * 量产计划从某个状态<b>允许</b>迁到哪些状态。
   *
   * <p>与 V268 迁移里 {@code guard_mass_production_plan_status()} 的 CASE 分支<b>逐条同构</b> ——
   * 服务层先挡一道（给出可读的 400），数据库层再挡一道（挡住绕过服务的写入）。
   */
  public static Set<MassProductionPlanStatus> allowedTargets(MassProductionPlanStatus from) {
    return switch (from) {
      case DRAFT -> ordered(MassProductionPlanStatus.DECIDED, MassProductionPlanStatus.CANCELLED);
      case DECIDED ->
          ordered(MassProductionPlanStatus.BOM_FROZEN, MassProductionPlanStatus.CANCELLED);
      case BOM_FROZEN ->
          ordered(MassProductionPlanStatus.SKU_FILED, MassProductionPlanStatus.CANCELLED);
      case SKU_FILED ->
          ordered(MassProductionPlanStatus.RELEASED, MassProductionPlanStatus.CANCELLED);
      case RELEASED -> ordered(MassProductionPlanStatus.CLOSED);
      // 🔴 已关闭 / 已取消是终态：没有出边，不提供「恢复」。
      case CLOSED -> ordered();
      case CANCELLED -> ordered();
    };
  }

  /** 量产计划状态是否终态。 */
  public static boolean isTerminalStatus(MassProductionPlanStatus status) {
    return status == MassProductionPlanStatus.CLOSED || status == MassProductionPlanStatus.CANCELLED;
  }

  /** 校验一次量产计划状态迁移；不合法就 400。 */
  public static void requireTransition(MassProductionPlanStatus from, MassProductionPlanStatus to) {
    if (from == to) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "目标状态与当前状态相同（" + from.label() + "），这不是一次状态迁移");
    }
    Set<MassProductionPlanStatus> allowed = allowedTargets(from);
    if (!allowed.contains(to)) {
      String targets =
          allowed.isEmpty()
              ? "无（已关闭 / 已取消是终态，不提供恢复）"
              : allowed.stream().map(MassProductionPlanStatus::label).toList().toString();
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "量产计划不能从「" + from.label() + "」迁到「" + to.label() + "」。允许的目标状态：" + targets);
    }
  }

  // ================================================================ 量产决策的举证

  /**
   * 量产决策必须有举证。
   *
   * <p>🔴 决策理由<b>必填</b>：一条没有理由的量产决策，后来没人能说清「当时为什么决定量产」。
   * <br>
   * 🔴 上游转量产建议是 {@code NO_GO} 而本计划决策是 {@code GO} 时，
   * <b>必须</b>写覆盖理由：那是<b>刻意</b>推翻上游结论，必须留下痕迹。
   *
   * @param upstreamDecision 上游 6.6.2.1 的建议结论（{@code GO} / {@code NO_GO} / 空串=取不到）
   */
  public static void requireDecisionEvidence(
      MassProductionDecision decision,
      MassProductionDecisionSource source,
      String rationale,
      String overrideReason,
      String upstreamDecision) {
    if (decision == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "量产决策结论必填（转量产 / 暂缓量产）");
    }
    if (source == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "量产决策来源必填（跟随上游建议 / 人工覆盖）");
    }
    if (rationale == null || rationale.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "量产决策理由必填：一条没有理由的量产决策，后来没人能说清「当时为什么决定量产」");
    }
    if ("NO_GO".equals(upstreamDecision) && decision == MassProductionDecision.GO) {
      if (overrideReason == null || overrideReason.isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "上游 6.6.2.1 的转量产建议是「暂不建议转量产」，而本计划要「转量产」："
                + "这是刻意推翻上游结论，必须写明覆盖理由");
      }
      if (source != MassProductionDecisionSource.MANUAL_OVERRIDE) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "覆盖上游「暂不建议转量产」的结论时，决策来源必须是「人工覆盖上游建议」");
      }
    }
  }

  // ================================================================ 建档请求的重试纪律

  /**
   * 建档请求当前状态<b>允许不允许</b>再次提交。
   *
   * <p>🔴 {@code CONFIRMED}（已建成）不许再提交：再提一次就是建第二个 SKU。
   * <br>
   * 🔴 {@code UNKNOWN}（结果未知）<b>也不许</b>再提交：catalog 的建档端点
   * <b>不接受幂等键</b>，超时 / 断连时无法判断它到底建没建 ⇒ 盲目重试可能建出重复 SKU，
   * 必须先人工去商品中心核对。
   * <br>
   * 🔴 {@code SUBMITTED}（已提交、结果未回）不许再提交：同一条请求还在飞。
   * <br>
   * {@code PENDING} / {@code FAILED} / {@code PARTIAL} 可以再提交：
   * 前两者 SKU 没建成，{@code PARTIAL} 是「SKU 建成了但报关属性没写进去」，重试只补报关属性。
   */
  public static void requireFilingResubmittable(MassProductionFilingStatus status) {
    switch (status) {
      case CONFIRMED ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "这个建档请求已经建成了（商品中心已返回 SKU），不能再提交：再提一次就是建第二个 SKU");
      case UNKNOWN ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "上一次提交的结果未知（商品中心建没建成功无法判断），不能盲目重试："
                  + "商品中心的建档端点不接受幂等键，重试可能建出重复 SKU。"
                  + "请先到商品中心核对该编码是否已存在，再决定怎么收口");
      case SUBMITTED ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT, "这个建档请求还在提交中，等它回来再试");
      case PENDING, FAILED, PARTIAL -> {
        // 允许重试。
      }
    }
  }

  /** 建档请求是否已经到了「建成了」的状态（用于计划能否推进到「SKU 已建档」）。 */
  public static boolean isFilingConfirmed(MassProductionFilingStatus status) {
    return status == MassProductionFilingStatus.CONFIRMED;
  }

  // ================================================================ 属性值的形状

  /**
   * 属性行的取值形状必须与 catalog 一致。
   *
   * <p>catalog 的 {@code saveSku} 逐字要求：{@code SET} 必须带值，
   * {@code CLEAR} / {@code INHERIT} 必须不带值（继承或清空时附带取值会被 catalog 拒）。
   */
  public static void requireAttributeValues(String valueState, List<String> values) {
    List<String> safe = values == null ? List.of() : values;
    boolean hasValue = safe.stream().anyMatch(v -> v != null && !v.isBlank());
    if ("SET".equals(valueState)) {
      if (!hasValue) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "属性状态是「设置」时必须给出取值（设置值不能为空，请使用清空）");
      }
      return;
    }
    if (hasValue) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "属性状态是「" + valueState + "」时不能附带取值（继承或清空时不能附带值）");
    }
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

  /** 数量必须 &gt; 0。 */
  public static void requirePositiveQuantity(BigDecimal quantity) {
    if (quantity == null || quantity.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数量必须大于 0");
    }
  }

  /** 金额必须 &gt; 0 —— 一个 0 元的目标成本不是目标。 */
  public static void requirePositiveAmount(BigDecimal amount, String what) {
    if (amount == null || amount.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + "必须大于 0（0 不是目标）");
    }
  }

  // ================================================================ 成本偏差

  /**
   * 成本偏差额 = 试产实测成本 − 量产目标成本（<b>同一币种内</b>相减）。
   *
   * <p>🔴 目标成本取不到就返回 {@code null} —— 本服务<b>不做固定汇率折算</b>，
   * 也<b>不拿 0 顶替</b>。
   */
  public static BigDecimal targetCostVarianceAmount(
      BigDecimal observedCost, BigDecimal targetCost) {
    if (observedCost == null || targetCost == null) {
      return null;
    }
    return observedCost.subtract(targetCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 成本偏差率 = (试产实测成本 − 量产目标成本) ÷ 量产目标成本（<b>比例</b>，单次 HALF_UP 到 6 位小数）。
   *
   * <p>🔴 目标成本取不到 ⇒ 偏差率取不到，<b>不拿 0 顶替</b>。
   */
  public static BigDecimal targetCostVarianceRate(
      BigDecimal observedCost, BigDecimal targetCost) {
    if (targetCost == null) {
      return null;
    }
    if (targetCost.signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "量产目标成本必须大于 0（拿 0 当分母算出来的偏差率没有意义）");
    }
    if (observedCost == null) {
      return null;
    }
    return observedCost
        .subtract(targetCost)
        .divide(targetCost, RATE_SCALE, RoundingMode.HALF_UP);
  }

  /** 保留顺序的不可变集合。 */
  private static <T> Set<T> ordered(T... values) {
    return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
  }
}
