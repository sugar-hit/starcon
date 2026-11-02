package com.starcon.catalog;

import com.starcon.catalog.PriceListItemModels.SelectionState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 5.1.1.2 价目项：**纯规则**部分（无 IO、无租户上下文，可单测）。
 *
 * <p>本类回答六个问题：
 *
 * <ol>
 *   <li>SKU 编码 / 单价 / 数量门槛 / 优先级怎么归一化；
 *   <li>生效区间怎么判定（<b>半开区间</b>）与两条区间怎么判重叠；
 *   <li><b>阶梯价怎么选档</b>（数量 → 适用档位）；
 *   <li><b>同优先级多条有效价目怎么判歧义</b>（蓝图加粗句）；
 *   <li>按百分比怎么换算（<b>BigDecimal 全程</b>）；
 *   <li>批量操作预览里每一行算成什么样。
 * </ol>
 *
 * <h2>🔴 纪律一：选档先于选优先级</h2>
 *
 * <p>同一个 SKU 的多行**不一定是冲突** —— 那是阶梯价。
 * 所以解析的顺序必须是：
 *
 * <ol>
 *   <li><b>先选档</b>：在 {@code min_quantity <= 数量} 的行里取 {@code min_quantity} 最大的一档
 *       （数量低于最低档 ⇒ 没有适用档位，如实说「不适用」，不猜）；
 *   <li><b>再选优先级</b>：档位内取 {@code priority} 最小的那条；
 *       <b>若最小的 priority 命中多条 ⇒ 歧义</b>。
 * </ol>
 *
 * <p>顺序反了（先看优先级）会把「基础档 + 满 100 件档」判成冲突，
 * 于是阶梯价一上线就被自己的冲突报告判成冲突 —— 那是最容易写错的一处。
 *
 * <h2>🔴 纪律二：同优先级多条 ⇒ 歧义，绝不随机取</h2>
 *
 * <p>蓝图加粗句：「同优先级多条有效价目必须报错让用户选，不随机取」。
 * 本类的落法：{@link #resolve} 在歧义时把 {@code selected} 置为 {@code null}
 * 并把 {@code ambiguous} 置为 {@code true} —— <b>没有任何一条兜底路径会替它挑一条</b>。
 * 候选清单由调用方带出去（409 的 body），让人来选。
 *
 * <h2>🔴 纪律三：金额全程 BigDecimal，末位才定标</h2>
 *
 * <p>单价与数量门槛在库里都是 {@code NUMERIC(19,6)}。计算过程用 BigDecimal，
 * <b>只有最后落库前</b>才 {@code setScale(6, HALF_UP)} —— 中途定标会引入
 * 「算一次舍一次」���累计误差（AGENTS.md：禁浮点记账）。
 */
public final class PriceListItemRules {

  private PriceListItemRules() {}

  /** 金额与数量的标度：与库的 {@code NUMERIC(19,6)} 逐字对齐。 */
  public static final int SCALE = 6;

  /** SKU 编码的字符集：大写字母、数字、短横线与下划线（与 3.1.2.1 的 SKU 编码同一口径）。 */
  private static final String SKU_PATTERN = "^[A-Z0-9][A-Z0-9_-]{0,63}$";

  // ================================================================ 归一化

  /**
   * 归一化 SKU 编码：去空白 + 转大写。
   *
   * @throws IllegalArgumentException 编码为空或字符集不合法
   */
  public static String normalizeSkuCode(String raw) {
    var code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if (code.isEmpty()) throw new IllegalArgumentException("SKU 编码必填");
    if (!code.matches(SKU_PATTERN)) {
      throw new IllegalArgumentException(
          "SKU 编码只能是大写字母、数字、短横线与下划线（最多 64 位，首位不能是分隔符）");
    }
    return code;
  }

  /**
   * 归一化单价：必须 &gt;= 0，标度不超过 {@value #SCALE}。
   *
   * @throws IllegalArgumentException 单价为空、为负、或小数位超出
   */
  public static BigDecimal normalizePrice(BigDecimal raw, String label) {
    if (raw == null) throw new IllegalArgumentException(label + "必填");
    if (raw.signum() < 0) throw new IllegalArgumentException(label + "不能是负数");
    if (raw.scale() > SCALE) {
      throw new IllegalArgumentException(
          label + "最多 " + SCALE + " 位小数（库里是 NUMERIC(19," + SCALE + ")，位数更多会写不进去）");
    }
    return raw.setScale(SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 归一化数量门槛：必须 &gt;= 0（空 = 0 = 基础档），标度不超过 {@value #SCALE}。
   *
   * @throws IllegalArgumentException 门槛为负或小数位超出
   */
  public static BigDecimal normalizeQuantity(BigDecimal raw, String label) {
    if (raw == null) return BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY);
    if (raw.signum() < 0) throw new IllegalArgumentException(label + "不能是负数");
    if (raw.scale() > SCALE) {
      throw new IllegalArgumentException(
          label + "最多 " + SCALE + " 位小数（库里是 NUMERIC(19," + SCALE + ")，位数更多会写不进去）");
    }
    return raw.setScale(SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 归一化优先级：空 = 0。**数字越小优先级越高**（与 3.6.2.1 供应商报价同一约定）。
   */
  public static int normalizePriority(Integer raw) {
    return raw == null ? 0 : raw;
  }

  /** 优先级的展示文案（把「越小越优先」写出来，别让人猜）。 */
  public static String priorityLabel(int priority) {
    return priority + "（数字越小越优先）";
  }

  // ================================================================ 生效区间

  /** 生效区间是否合法：终点为空 = 长期有效；终点存在时必须晚于起点。 */
  public static boolean validInterval(OffsetDateTime from, OffsetDateTime to) {
    if (from == null) return false;
    return to == null || to.isAfter(from);
  }

  /** 此刻是否生效（半开区间 [from, to)）。 */
  public static boolean effectiveNow(OffsetDateTime from, OffsetDateTime to, OffsetDateTime now) {
    if (from == null || now == null) return false;
    if (now.isBefore(from)) return false;
    return to == null || now.isBefore(to);
  }

  /**
   * 两条生效区间是否重叠（半开区间口径：端点相接**不算**重叠）。
   *
   * <p>「A 到 10-01 止、B 从 10-01 起」⇒ 不重叠 ⇒ 那段时间里价格是唯一的。
   */
  public static boolean overlaps(
      OffsetDateTime aFrom, OffsetDateTime aTo, OffsetDateTime bFrom, OffsetDateTime bTo) {
    if (aFrom == null || bFrom == null) return false;
    if (aTo != null && !aTo.isAfter(bFrom)) return false;
    if (bTo != null && !bTo.isAfter(aFrom)) return false;
    return true;
  }

  /** 两条区间的重叠段（起点 = 较晚的那个，终点 = 较早的那个；任一为空 = 无限）。 */
  public static OffsetDateTime[] overlapOf(
      OffsetDateTime aFrom, OffsetDateTime aTo, OffsetDateTime bFrom, OffsetDateTime bTo) {
    var from = aFrom.isAfter(bFrom) ? aFrom : bFrom;
    OffsetDateTime to = null;
    if (aTo != null && bTo != null) to = aTo.isBefore(bTo) ? aTo : bTo;
    else if (aTo != null) to = aTo;
    else if (bTo != null) to = bTo;
    return new OffsetDateTime[] {from, to};
  }

  // ================================================================ 阶梯与优先级（蓝图加粗句）

  /** 参与解析的一条候选（纯规则用的最小形状）。 */
  public record Candidate(UUID id, BigDecimal minQuantity, int priority, BigDecimal unitPrice) {}

  /**
   * 解析结果。
   *
   * @param pool 适用档位（同 {@code minQuantity}）的候选
   * @param selected 唯一命中的候选；<b>歧义时为 null</b>
   * @param ambiguous 是否歧义（同优先级多条有效价目）
   * @param states 与入参 {@code candidates} 等长同序的选择状态
   */
  public record Resolution(
      List<Candidate> pool,
      Candidate selected,
      boolean ambiguous,
      List<String> states,
      BigDecimal appliedMinQuantity) {}

  /**
   * 选档 + 选优先级。
   *
   * @param candidates 该 SKU 在该价目表里此刻生效的全部候选
   * @param quantity 本次购买数量；<b>为空</b>时只认基础档（{@code min_quantity = 0}），
   *     表里只有阶梯价 ⇒ 解析不出档位（如实说「不适用」，不猜）
   */
  public static Resolution resolve(List<Candidate> candidates, BigDecimal quantity) {
    var states = new ArrayList<String>();
    if (candidates == null || candidates.isEmpty()) {
      return new Resolution(List.of(), null, false, states, null);
    }
    // 1) 选档
    List<Candidate> applicable = new ArrayList<>();
    for (var candidate : candidates) {
      var min = candidate.minQuantity() == null ? BigDecimal.ZERO : candidate.minQuantity();
      boolean ok = quantity == null ? min.signum() == 0 : min.compareTo(quantity) <= 0;
      if (ok) applicable.add(candidate);
    }
    if (applicable.isEmpty()) {
      for (int i = 0; i < candidates.size(); i++) states.add(SelectionState.NOT_APPLICABLE.name());
      return new Resolution(List.of(), null, false, states, null);
    }
    var maxMin =
        applicable.stream()
            .map(c -> c.minQuantity() == null ? BigDecimal.ZERO : c.minQuantity())
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    var pool =
        applicable.stream()
            .filter(c -> (c.minQuantity() == null ? BigDecimal.ZERO : c.minQuantity()).compareTo(maxMin) == 0)
            .toList();
    // 2) 选优先级
    int best = pool.stream().mapToInt(Candidate::priority).min().orElse(0);
    long bestCount = pool.stream().filter(c -> c.priority() == best).count();
    boolean ambiguous = bestCount > 1;
    Candidate selected = null;
    if (!ambiguous) {
      for (var candidate : pool) {
        if (candidate.priority() == best) selected = candidate;
      }
    }
    // 3) 逐条标状态（与入参同序）
    for (var candidate : candidates) {
      var min = candidate.minQuantity() == null ? BigDecimal.ZERO : candidate.minQuantity();
      boolean inPool = min.compareTo(maxMin) == 0 && applicable.contains(candidate);
      if (!inPool) {
        states.add(SelectionState.NOT_APPLICABLE.name());
      } else if (candidate.priority() == best) {
        states.add(ambiguous ? SelectionState.AMBIGUOUS.name() : SelectionState.SELECTED.name());
      } else {
        states.add(SelectionState.LOWER_PRIORITY.name());
      }
    }
    return new Resolution(pool, selected, ambiguous, states, maxMin);
  }

  /**
   * 同优先级多条有效价目的歧义说明（写给运营看的一句话）。
   *
   * @param skuCode SKU 编码
   * @param minQuantity 命中的档位门槛
   * @param count 并列条数
   */
  public static String ambiguityProblem(String skuCode, BigDecimal minQuantity, int count) {
    return "SKU "
        + skuCode
        + " 在数量门槛 "
        + quantityText(minQuantity)
        + " 这一档上有 "
        + count
        + " 条同优先级的有效价目 —— 必须由你指定用哪一条，系统不会随机取一条";
  }

  // ================================================================ 金额换算

  /**
   * 按涨跌幅换算单价：{@code base × (1 + percent / 100)}。
   *
   * <p>🔴 全程 BigDecimal，<b>只有最后一步</b>才定标到 {@value #SCALE} 位
   * （{@link RoundingMode#HALF_UP}）。中途定标会「算一次舍一次」。
   *
   * @param base 基准价
   * @param percent 涨跌幅百分比（{@code 10} = 涨 10%，{@code -10} = 降 10%）
   * @throws IllegalArgumentException 基准价为空，或算出来是负数
   */
  public static BigDecimal applyPercent(BigDecimal base, BigDecimal percent) {
    if (base == null) throw new IllegalArgumentException("没有基准价，无法按百分比换算");
    if (percent == null) throw new IllegalArgumentException("按百分比换算必须给涨跌幅");
    var factor = BigDecimal.ONE.add(percent.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
    var result = base.multiply(factor).setScale(SCALE, RoundingMode.HALF_UP);
    if (result.signum() < 0) {
      throw new IllegalArgumentException(
          "按 " + percent.stripTrailingZeros().toPlainString() + "% 换算后是负数（"
              + result.toPlainString()
              + "）—— 价格不能是负数");
    }
    return result;
  }

  /** 数量的展示文本（去掉多余的 0，基础档显示「基础档」）。 */
  public static String quantityText(BigDecimal raw) {
    if (raw == null) return "基础档";
    var stripped = raw.stripTrailingZeros();
    if (stripped.signum() == 0) return "基础档";
    return stripped.toPlainString();
  }
}
