package com.starcon.catalog;

import com.starcon.catalog.SkuCostCompositionModels.CostMethod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 5.2.1.1 成本构成的**纯计算**规则（无 IO，可单测）。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **成本口径必须在页面上明示用了哪种成本法**，否则同一 SKU 今天显示毛利 30% 明天 25% 无法解释。
 *
 * </blockquote>
 *
 * ⇒ 成本法不是"页面上的一个下拉框"，而是**参与算术**的东西：同一份到货事实，
 * 移动加权 / 先进先出 / 批次算出来的单位成本**是不同的**。本类把这三种算法写死在这里，
 * 三处落地（列表、明细、历史快照）**共用同一份实现** —— 三处各算一次的话，
 * 迟早会出现"明细显示 12.00、历史快照里却是 12.50"这种没法解释的对不上。
 *
 * <h2>🔴 金额与数量一律 BigDecimal，禁浮点</h2>
 *
 * <p>AGENTS.md：禁止浮点记账。本类的每一次除法都带 scale 与舍入模式：
 * <ul>
 *   <li>中间比例（{@code quantity_per_base} 折算、先进先出的分摊份额）用 {@link #RATIO_SCALE} 位、
 *       {@link RoundingMode#HALF_UP}；
 *   <li>对外的金额（单位成本、合计）用 {@link #MONEY_SCALE} 位（4 位，与 V137 / V32 的
 *       {@code numeric(19,4)} 同精度）。
 * </ul>
 *
 * <h2>三种成本法的确切口径</h2>
 *
 * <p>三者作用在**同一份事实**上：catalog 同库 {@code vendor_delivery_record}（V137）的
 * **实际到货记录**（数量 / 金额 / 币种 / 实际到货日）。每一行到货记录先折算到 SKU 的
 * **基础单位**（{@code baseQuantity}），再按成本法加权：
 *
 * <ul>
 *   <li><b>MOVING_AVERAGE 移动加权</b>：窗口内全部有效到货的加权平均单价 = Σ金额 ÷ Σ基础数量。
 *   <li><b>FIFO 先进先出</b>：按到货日升序消耗到**目标数量**后的加权平均单价。
 *       目标数量取**最近一次到货的基础数量**（代理值）：本服务没有出库消耗台账
 *       （07 库存未交付），拿不到"现在还剩多少"，所以取"最近那批有多少"作为
 *       正在动的那一层的大小。
 *   <li><b>LOT 批次</b>：每一条到货记录即一个批次；单位成本取**目标批次**（默认最近一批）的单价，
 *       响应同时给全部批次清单。
 * </ul>
 *
 * <p>同一份事实下三者给出不同结果，这正是蓝图要"明示口径"的原因：
 * 一批 10 元的老货 + 一批 20 元的新货，移动加权 15.00、先进先出 10.00、批次 20.00。
 */
public final class SkuCostCompositionRules {

  /** 对外金额精度：4 位小数（0.0001 货币单位）。与 V137 / V32 的 {@code numeric(19,4)} 同精度。 */
  public static final int MONEY_SCALE = 4;

  /** 中间比例精度：折算与分摊份额的中间结果保留 10 位，避免二次除法把精度吃掉。 */
  public static final int RATIO_SCALE = 10;

  /** 舍入模式：四舍五入（HALF_UP）。与本仓其它金额计算保持一致。 */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  private SkuCostCompositionRules() {}

  /**
   * 一条到货批次（已折算到基础单位）。
   *
   * @param baseQuantity 基础单位数量。{@code quantity}（采购单位）× 换算比例。
   * @param currency 币种。**跨币种绝不相加**（本库没有汇率表，汇率是 12 财务的资产）。
   */
  public record DeliveryLot(
      UUID id,
      String deliveryRef,
      LocalDate receivedDate,
      BigDecimal quantity,
      BigDecimal baseQuantity,
      BigDecimal amount,
      String currency) {

    /** 这一批的单价（每基础单位）。数量为 0 时返回 null（DB 层已挡住 quantity > 0）。 */
    public BigDecimal unitCost() {
      if (baseQuantity == null || baseQuantity.signum() == 0) return null;
      return amount.divide(baseQuantity, MONEY_SCALE, ROUNDING);
    }
  }

  /**
   * 一次加权的结果。
   *
   * @param unitCost 单位成本（每基础单位）。null = 该币种下没有任何可用批次。
   * @param totalAmount 参与计算的金额合计
   * @param totalBaseQuantity 参与计算的基础数量合计
   * @param lotCount 参与的批次数
   * @param selectedLot 批次法选中的那一批（其它成本法为 null）
   */
  public record WeightedCost(
      BigDecimal unitCost,
      BigDecimal totalAmount,
      BigDecimal totalBaseQuantity,
      int lotCount,
      DeliveryLot selectedLot) {}

  /** 到货日升序；同日按 id 升序 —— 顺序必须确定，否则同一份事实两次算出不同结果。 */
  private static final Comparator<DeliveryLot> BY_RECEIVED =
      Comparator.comparing(
              (DeliveryLot lot) -> lot.receivedDate() == null ? LocalDate.MAX : lot.receivedDate())
          .thenComparing(lot -> lot.id() == null ? new UUID(0L, 0L) : lot.id());

  /**
   * 按成本法加权。
   *
   * @param method 成本法
   * @param lots 该 SKU 在窗口内的有效到货记录（**已按币种过滤**）
   * @return 加权结果；{@code lots} 为空时 {@code unitCost} 为 null（= 该分项取不到，不是 0）
   */
  public static WeightedCost weighted(CostMethod method, List<DeliveryLot> lots) {
    if (lots == null || lots.isEmpty()) {
      return new WeightedCost(null, BigDecimal.ZERO, BigDecimal.ZERO, 0, null);
    }
    var ordered = new ArrayList<>(lots);
    ordered.sort(BY_RECEIVED);
    return switch (method) {
      case MOVING_AVERAGE -> movingAverage(ordered);
      case FIFO -> fifo(ordered);
      case LOT -> lot(ordered);
    };
  }

  /**
   * 移动加权：Σ金额 ÷ Σ基础数量。
   *
   * <p>整个窗口里的每一条到货都参与，权重是数量 —— 这就是"移动"的意思：
   * 每来一批新货，历史批次的价格被新批次稀释。
   */
  private static WeightedCost movingAverage(List<DeliveryLot> ordered) {
    BigDecimal amount = BigDecimal.ZERO;
    BigDecimal base = BigDecimal.ZERO;
    for (var lot : ordered) {
      amount = amount.add(nz(lot.amount()));
      base = base.add(nz(lot.baseQuantity()));
    }
    if (base.signum() == 0) {
      return new WeightedCost(null, amount, base, ordered.size(), null);
    }
    return new WeightedCost(
        amount.divide(base, MONEY_SCALE, ROUNDING), amount, base, ordered.size(), null);
  }

  /**
   * 先进先出：按到货日升序消耗到**目标数量**后的加权平均单价。
   *
   * <p>目标数量 = 最近一次到货的基础数量（代理值，见类注释）。消耗过程：
   * 从最早的一批开始取，取满目标数量为止；最后一批取不满就只取它剩下的部分。
   *
   * <p>单价来自各批自己的 {@code amount ÷ baseQuantity}，再按消耗份额加权 ——
   * 这样"消耗到一半的那一批"贡献的金额正好是它那一半的钱，而不是整批的钱。
   */
  private static WeightedCost fifo(List<DeliveryLot> ordered) {
    var last = ordered.get(ordered.size() - 1);
    BigDecimal target = nz(last.baseQuantity());
    BigDecimal remaining = target;
    BigDecimal amount = BigDecimal.ZERO;
    BigDecimal base = BigDecimal.ZERO;
    int consumed = 0;
    for (var lot : ordered) {
      if (remaining.signum() <= 0) break;
      BigDecimal lotBase = nz(lot.baseQuantity());
      if (lotBase.signum() <= 0) continue;
      BigDecimal take = lotBase.min(remaining);
      BigDecimal lotUnit = nz(lot.amount()).divide(lotBase, RATIO_SCALE, ROUNDING);
      amount = amount.add(lotUnit.multiply(take).setScale(MONEY_SCALE, ROUNDING));
      base = base.add(take);
      remaining = remaining.subtract(take);
      consumed++;
    }
    if (base.signum() == 0) {
      return new WeightedCost(null, BigDecimal.ZERO, BigDecimal.ZERO, consumed, null);
    }
    return new WeightedCost(
        amount.divide(base, MONEY_SCALE, ROUNDING), amount, base, consumed, null);
  }

  /**
   * 批次：取目标批次（默认最近一批）的单价。
   *
   * <p>批次法**不**做加权 —— 它的口径是"这一批货就是这个价"。这是它与前两者的本质区别，
   * 也正是同一份事实下三者结果不同的原因。
   */
  private static WeightedCost lot(List<DeliveryLot> ordered) {
    var selected = ordered.get(ordered.size() - 1);
    BigDecimal unit = selected.unitCost();
    BigDecimal base = nz(selected.baseQuantity());
    return new WeightedCost(unit, nz(selected.amount()), base, 1, selected);
  }

  /** 批次清单（批次法时页面要能看清"有哪些批次、哪一批被选中"）。 */
  public static List<DeliveryLot> orderedLots(List<DeliveryLot> lots) {
    var ordered = new ArrayList<>(lots == null ? List.<DeliveryLot>of() : lots);
    ordered.sort(BY_RECEIVED);
    return ordered;
  }

  /**
   * 折算到基础单位。
   *
   * @param quantity 采购单位数量
   * @param quantityPerBase 「1 个采购单位等于多少个基础单位」；{@code null} 表示**折算不出来**。
   * @return 基础数量；折算不出来时返回 null（调用方必须把该行排除并如实报出排除行数）
   */
  public static BigDecimal toBaseQuantity(BigDecimal quantity, BigDecimal quantityPerBase) {
    if (quantity == null || quantityPerBase == null) return null;
    return quantity.multiply(quantityPerBase).setScale(6, ROUNDING);
  }

  /** null 安全取零。 */
  public static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /**
   * 合计（只加**本币种**的可用分项）。
   *
   * <p>🔴 跨币种绝不相加：调用方只把同币种的金额传进来。
   */
  public static BigDecimal total(List<BigDecimal> amounts) {
    BigDecimal sum = BigDecimal.ZERO;
    for (var amount : amounts) sum = sum.add(nz(amount));
    return sum.setScale(MONEY_SCALE, ROUNDING);
  }
}
