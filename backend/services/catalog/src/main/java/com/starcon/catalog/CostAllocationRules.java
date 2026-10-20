package com.starcon.catalog;

import com.starcon.catalog.CostAllocationModels.BasisRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * 5.2.1.3 成本分摊的**纯计算**规则（无 IO，可单测）。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **分摊规则变更会导致历史利润变化**，因此调整必须留历史版本，不能就地覆盖。
 *
 * </blockquote>
 *
 * ⇒ 分摊规则**必须真的参与算术**，而且**算术必须可复现**：
 * 同一份货件事实 + 同一条规则 ⇒ 逐行分摊额**逐分相同**。
 * 做不到这一点，「重算与差异对比」就没有意义 —— 每次重算都算出不一样的数，
 * 差异对比里全是噪声，运营分不清「规则变了」和「随机数变了」。
 *
 * <h2>🔴 逐行之和必须严格等于货件成本总额</h2>
 *
 * <p>分摊是<b>拆分</b>不是<b>缩水</b>。数学上 Σ(总额 × 行基数 ÷ Σ基数) = 总额，
 * 但逐行四舍五入到 4 位小数后会差几分钱 —— 而那几分钱落在谁的头上是<b>不确定</b>的
 * （取决于先舍入谁）。⇒ 本类用**最大余数法**：
 *
 * <ol>
 *   <li>把总额换算成 <b>0.0001 为单位的整数</b>（{@code totalUnits}）；
 *   <li>每行按基数占比算出它应得的<b>单位数</b>（保留 10 位小数做中间精度）；
 *   <li>每行<b>向下取整</b>到整数单位 —— 这一步最多损失 {@code n} 个单位（n = 行数）；
 *   <li>把剩下的单位按<b>小数部分从大到小</b>逐个补回去（并列时按 SKU id 升序，
 *       保证顺序确定 ⇒ 结果可复现）。
 * </ol>
 *
 * <p>这样 Σ分摊额 <b>逐分等于</b>总额，且与「先算谁」无关。
 *
 * <h2>🔴 基数取不到 ≠ 分摊到 0</h2>
 *
 * <p>一行取不到基数（没申报重量、没维护成本构成）时：它<b>不参与</b>分母
 * （否则分母被一个假 0 拉偏，剩下的行多分），并且<b>明确</b>记成「基数不可得」。
 * 所有行都取不到基数时<b>抛错</b>（{@link BasisUnavailableException}）——
 * 摊出一张全 0 的单会让下游以为「这个货件不用花钱」，而真实情况是「没有基数可分」。
 *
 * <h2>🔴 金额与数量一律 BigDecimal，禁浮点</h2>
 *
 * <p>AGENTS.md：禁止浮点记账。本类的每一次除法都带 scale 与舍入模式：
 * <ul>
 *   <li>对外的金额（分摊额）用 {@link #MONEY_SCALE} 位（4 位，与 V137 / V215 的
 *       {@code numeric(19,4)} 同精度）；
 *   <li>中间比例（行应得单位数）用 {@link #RATIO_SCALE} 位、{@link RoundingMode#HALF_UP}。
 * </ul>
 */
public final class CostAllocationRules {

  /** 对外金额精度：4 位小数（0.0001 货币单位）。与 V137 / V215 的 {@code numeric(19,4)} 同精度。 */
  public static final int MONEY_SCALE = 4;

  /** 中间比例精度：行应得单位数的中间结果保留 10 位，避免二次除法把精度吃掉。 */
  public static final int RATIO_SCALE = 10;

  /** 舍入模式：四舍五入（HALF_UP）。与本仓其它金额计算保持一致。 */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** 向下取整的舍入模式：取整到「0.0001 为单位的整数」时用它。 */
  private static final RoundingMode FLOOR = RoundingMode.FLOOR;

  private CostAllocationRules() {}

  /**
   * 一行货件（已折算到 SKU 基础单位）。
   *
   * @param baseQuantity 本行数量（QUANTITY 规则的基数）
   * @param weightGrams 本行毛重（克，WEIGHT 规则的基数）
   * @param volumeCm3 本行体积（立方厘米，VOLUME 规则的基数）
   * @param amountBasis 本行货值（AMOUNT 规则的基数）
   * @param basisAvailable 基数是否可得（false 时上面三个基数一律不参与分母）
   * @param basisNote 基数来源 / 不可得的原因
   */
  public record BasisLine(
      UUID skuId,
      BigDecimal baseQuantity,
      BigDecimal weightGrams,
      BigDecimal volumeCm3,
      BigDecimal amountBasis,
      boolean basisAvailable,
      String basisNote) {}

  /**
   * 一行的分摊结果。
   *
   * @param basisValue 这一版实际采用的基数；不可得时为 <b>null</b>（不是 0）
   * @param share 占比（0~1）
   * @param allocatedAmount 计算分摊额
   */
  public record AllocatedLine(
      UUID skuId,
      BigDecimal basisValue,
      BigDecimal share,
      boolean basisAvailable,
      String basisNote,
      BigDecimal allocatedAmount) {}

  /**
   * 一次分摊的总体结果。
   *
   * @param lines 逐行结果
   * @param totalCost 货件成本总额
   * @param basisTotal 参与分摊的基数合计
   * @param eligibleCount 参与分摊的行数
   */
  public record AllocationOutcome(
      List<AllocatedLine> lines,
      BigDecimal totalCost,
      BigDecimal basisTotal,
      int eligibleCount) {}

  /**
   * 基数不可得（**所有行都摊不了**）。
   *
   * <p>这是「没有基数可分」，不是「这个货件不用花钱」—— 两者在页面上必须长得不一样。
   */
  public static class BasisUnavailableException extends RuntimeException {
    public BasisUnavailableException(String message) {
      super(message);
    }
  }

  /**
   * 按规则分摊。
   *
   * @param totalCost 货件成本总额
   * @param lines 货件的行
   * @param rule 分摊规则
   * @return 逐行分摊结果；{@code lines} 里所有行的分摊额之和 <b>严格等于</b> {@code totalCost}
   * @throws BasisUnavailableException 所有行都取不到基数
   */
  public static AllocationOutcome allocate(
      BigDecimal totalCost, List<BasisLine> lines, BasisRule rule) {
    if (totalCost == null || totalCost.signum() < 0) {
      throw new IllegalArgumentException("货件成本总额不能为负");
    }
    if (lines == null || lines.isEmpty()) {
      throw new BasisUnavailableException("货件没有可分摊的行");
    }
    if (rule == null) {
      throw new IllegalArgumentException("必须选一种分摊规则");
    }

    // 1) 每行取基数：取不到的行不参与分母，但保留在结果里（明确标「不可得」）。
    var basisBySku = new LinkedHashMap<UUID, BigDecimal>();
    var eligible = new ArrayList<BasisLine>();
    BigDecimal basisTotal = BigDecimal.ZERO;
    for (var line : lines) {
      var basis = basisOf(line, rule);
      basisBySku.put(line.skuId(), basis);
      if (basis != null && basis.signum() > 0) {
        eligible.add(line);
        basisTotal = basisTotal.add(basis);
      }
    }
    if (basisTotal.signum() <= 0) {
      throw new BasisUnavailableException(
          "这个货件在「"
              + rule.label()
              + "」口径下没有可用的分摊基数（"
              + unavailableSummary(lines, rule)
              + "），无法分摊。"
              + "请先补齐基数，或改用其它规则 —— 摊出一张全 0 的单会让下游以为这个货件不用花钱。");
    }

    // 2) 总额换算成 0.0001 为单位的整数 —— 整数单位上的「最大余数法」才保证逐分相等。
    BigDecimal cost = totalCost.setScale(MONEY_SCALE, ROUNDING);
    BigDecimal totalUnits = cost.movePointRight(MONEY_SCALE);
    BigDecimal basisTotalFinal = basisTotal;

    // 3) 每行应得的单位数（保留 10 位小数），再向下取整到整数单位。
    var unitsBySku = new LinkedHashMap<UUID, BigDecimal>();
    var fractionBySku = new LinkedHashMap<UUID, BigDecimal>();
    BigDecimal flooredSum = BigDecimal.ZERO;
    for (var line : eligible) {
      var basis = basisBySku.get(line.skuId());
      BigDecimal exactUnits =
          totalUnits.multiply(basis).divide(basisTotalFinal, RATIO_SCALE, ROUNDING);
      BigDecimal floored = exactUnits.setScale(0, FLOOR);
      unitsBySku.put(line.skuId(), floored);
      fractionBySku.put(line.skuId(), exactUnits.subtract(floored));
      flooredSum = flooredSum.add(floored);
    }

    // 4) 剩下的单位按小数部分从大到小补回去（并列时按 SKU id 升序 ⇒ 顺序确定、结果可复现）。
    BigDecimal remainderUnits = totalUnits.subtract(flooredSum);
    int unitsToGive = remainderUnits.intValueExact();
    if (unitsToGive > 0) {
      var order = new ArrayList<>(eligible);
      order.sort(
          Comparator.comparing((BasisLine l) -> fractionBySku.get(l.skuId()))
              .reversed()
              .thenComparing(l -> l.skuId() == null ? new UUID(0L, 0L) : l.skuId()));
      for (int i = 0; i < unitsToGive && i < order.size(); i++) {
        var skuId = order.get(i).skuId();
        unitsBySku.put(skuId, unitsBySku.get(skuId).add(BigDecimal.ONE));
      }
    }

    // 5) 还原成金额。
    var result = new ArrayList<AllocatedLine>();
    for (var line : lines) {
      var basis = basisBySku.get(line.skuId());
      boolean available = basis != null && basis.signum() > 0;
      BigDecimal units = available ? unitsBySku.get(line.skuId()) : BigDecimal.ZERO;
      BigDecimal share =
          available
              ? basis.divide(basisTotalFinal, RATIO_SCALE, ROUNDING)
              : BigDecimal.ZERO;
      result.add(
          new AllocatedLine(
              line.skuId(),
              available ? basis : null,
              share,
              available,
              line.basisNote(),
              units.movePointLeft(MONEY_SCALE).setScale(MONEY_SCALE, ROUNDING)));
    }
    return new AllocationOutcome(result, cost, basisTotalFinal, eligible.size());
  }

  /** 按规则取一行的基数；取不到（或为 0）时返回 null。 */
  private static BigDecimal basisOf(BasisLine line, BasisRule rule) {
    if (!line.basisAvailable()) return null;
    var value =
        switch (rule) {
          case WEIGHT -> line.weightGrams();
          case VOLUME -> line.volumeCm3();
          case AMOUNT -> line.amountBasis();
          case QUANTITY -> line.baseQuantity();
        };
    if (value == null || value.signum() <= 0) return null;
    return value;
  }

  /** 所有行都不可得时，把「为什么」说清楚（按规则点名缺哪种基数）。 */
  private static String unavailableSummary(List<BasisLine> lines, BasisRule rule) {
    var missing = new ArrayList<String>();
    for (var line : lines) {
      BigDecimal value =
          switch (rule) {
            case WEIGHT -> line.weightGrams();
            case VOLUME -> line.volumeCm3();
            case AMOUNT -> line.amountBasis();
            case QUANTITY -> line.baseQuantity();
          };
      if (value == null || value.signum() <= 0) {
        if (line.basisNote() != null && !line.basisNote().isBlank()) {
          if (!missing.contains(line.basisNote())) missing.add(line.basisNote());
        }
      }
    }
    if (missing.isEmpty()) return "每一行的基数都是 0";
    return String.join("；", missing);
  }

  // ================================================================ 人工微调

  /**
   * 把人工微调加到计算分摊额上。
   *
   * @param allocated 计算分摊额
   * @param adjustment 人工微调（可正可负）
   * @return 最终分摊额
   */
  public static BigDecimal finalAmount(BigDecimal allocated, BigDecimal adjustment) {
    return nz(allocated).add(nz(adjustment)).setScale(MONEY_SCALE, ROUNDING);
  }

  // ================================================================ 差异对比

  /**
   * 两个版本之间的差异对比。
   *
   * @param before 旧版本的行（SKU → 最终分摊额）
   * @param after 新版本的行（SKU → 最终分摊额）
   * @return 逐 SKU 的差异
   */
  public static List<CostAllocationModels.PricingCostAllocationDiffRow> diff(
      List<CostAllocationModels.PricingCostAllocationLineRow> before,
      List<CostAllocationModels.PricingCostAllocationLineRow> after) {
    var beforeBySku = new LinkedHashMap<UUID, CostAllocationModels.PricingCostAllocationLineRow>();
    for (var row : before == null ? List.<CostAllocationModels.PricingCostAllocationLineRow>of() : before) {
      beforeBySku.put(row.skuId(), row);
    }
    var afterBySku = new LinkedHashMap<UUID, CostAllocationModels.PricingCostAllocationLineRow>();
    for (var row : after == null ? List.<CostAllocationModels.PricingCostAllocationLineRow>of() : after) {
      afterBySku.put(row.skuId(), row);
    }

    var rows = new ArrayList<CostAllocationModels.PricingCostAllocationDiffRow>();
    var seen = new java.util.LinkedHashSet<UUID>();
    for (var row : after) {
      seen.add(row.skuId());
      var old = beforeBySku.get(row.skuId());
      if (old == null) {
        rows.add(
            new CostAllocationModels.PricingCostAllocationDiffRow(
                row.skuId(),
                row.skuCode(),
                row.skuName(),
                "ADDED",
                null,
                row.finalAmount(),
                null,
                "这一版新增了这一行"));
        continue;
      }
      var delta = nz(row.finalAmount()).subtract(nz(old.finalAmount()));
      rows.add(
          new CostAllocationModels.PricingCostAllocationDiffRow(
              row.skuId(),
              row.skuCode(),
              row.skuName(),
              delta.signum() == 0 ? "SAME" : "CHANGED",
              old.finalAmount(),
              row.finalAmount(),
              delta.setScale(MONEY_SCALE, ROUNDING),
              delta.signum() == 0 ? "分摊额没变" : "分摊额变了"));
    }
    for (var row : before) {
      if (seen.contains(row.skuId())) continue;
      rows.add(
          new CostAllocationModels.PricingCostAllocationDiffRow(
              row.skuId(),
              row.skuCode(),
              row.skuName(),
              "REMOVED",
              row.finalAmount(),
              null,
              null,
              "这一版没有这一行了（货件内容变了，或它不再参与分摊）"));
    }
    return rows;
  }

  // ================================================================ 小工具

  /** null 安全取零。 */
  public static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /** 逐行求和（scale 4）。 */
  public static BigDecimal sum(List<BigDecimal> values) {
    BigDecimal total = BigDecimal.ZERO;
    for (var value : values) total = total.add(nz(value));
    return total.setScale(MONEY_SCALE, ROUNDING);
  }

}
