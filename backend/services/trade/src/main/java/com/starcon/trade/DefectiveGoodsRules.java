package com.starcon.trade;

import static com.starcon.trade.DefectiveGoodsModels.DefectiveClaimType;
import static com.starcon.trade.DefectiveGoodsModels.DefectiveDispositionType;
import static com.starcon.trade.DefectiveGoodsModels.DefectiveInventoryAction;
import static com.starcon.trade.DefectiveGoodsModels.DefectiveOrderStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 6.4.2.2 的纯计算规则。
 *
 * <p><b>为什么单独成类</b>：这几条是本模块最容易被「顺手改一下」弄坏的地方
 * （可售量上限怎么算、放行量怎么来、索赔类型怎么推导、状态怎么流转），
 * 而它们**全都不需要数据库**。放进一个纯函数类里，就能用不依赖容器的单元测试钉住 ——
 * 服务层与数据库层各挡一道之外，规则本身还有第三道。
 *
 * <p><b>四条规则</b>：
 *
 * <ol>
 *   <li><b>可售量上限 = 合格量 + 让步接收量</b>（与 6.4.2.1 的口径同源）。
 *       处置**只作用在不合格量上**，不得侵占这一部分。</li>
 *   <li><b>可处置量 = 不合格量 − 已处置量</b>（已处置量含同一张质检单下的其它处置单，
 *       作废的单不计入）。</li>
 *   <li><b>放行量由处置类型决定</b>：让步接收与转促销品**整行**回可售池（放行量 = 该行数量），
 *       其余三类货都离仓或不可售（放行量 = 0）。</li>
 *   <li><b>索赔类型由处置类型推导</b>：不是自由文本，避免同一批货被记成两种索赔。</li>
 * </ol>
 */
public final class DefectiveGoodsRules {

  private DefectiveGoodsRules() {}

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  public static final int QUANTITY_SCALE = 6;

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  public static final int AMOUNT_SCALE = 4;

  /** 统一数量刻度（HALF_UP，与 6.4.2.1 / 8.2.2.2 同一口径）。 */
  public static BigDecimal scale(BigDecimal value) {
    if (value == null) {
      return null;
    }
    return value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /** 统一金额刻度（HALF_UP）。 */
  public static BigDecimal scaleAmount(BigDecimal value) {
    if (value == null) {
      return null;
    }
    return value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 可售量上限 = 合格量 + 让步接收量。
   *
   * <p>与 6.4.2.1 质检单上的 {@code sellableQuantity} 是**同一个口径**：
   * 让步接收量是「不合格里被批准放行的那部分」，它与合格量一样是可售的。
   */
  public static BigDecimal sellableCeiling(BigDecimal qualified, BigDecimal concession) {
    return scale(zero(qualified).add(zero(concession)));
  }

  /** 可处置量 = 不合格量 − 已处置量。 */
  public static BigDecimal remainingDefective(BigDecimal defective, BigDecimal disposed) {
    BigDecimal left = zero(defective).subtract(zero(disposed));
    return left.signum() < 0 ? BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP) : left;
  }

  /** 这个去向的货会不会回到可售池。 */
  public static boolean isReleasing(DefectiveDispositionType type) {
    return type.releases();
  }

  /**
   * 放行量：由处置类型**决定**，不由调用方决定。
   *
   * <p>让步接收与转促销品整行回可售池（放行量 = 该行数量）；
   * 报废 / 退换货 / 返修的货离仓或不可售（放行量 = 0）。
   */
  public static BigDecimal releaseFor(DefectiveDispositionType type, BigDecimal quantity) {
    return isReleasing(type) ? scale(quantity) : scale(BigDecimal.ZERO);
  }

  /** 处置类型 → 库存状态变更指令的动作。 */
  public static DefectiveInventoryAction actionFor(DefectiveDispositionType type) {
    return switch (type) {
      case RETURN_EXCHANGE -> DefectiveInventoryAction.RETURN_OUT;
      case REPAIR -> DefectiveInventoryAction.REPAIR_HOLD;
      case CONCESSION -> DefectiveInventoryAction.CONCESSION_RELEASE;
      case SCRAP -> DefectiveInventoryAction.SCRAP_OUT;
      case PROMOTION -> DefectiveInventoryAction.PROMOTION_RELEASE;
    };
  }

  /** 处置类型 → 索赔类型（推导，不是自由文本）。 */
  public static DefectiveClaimType claimTypeFor(DefectiveDispositionType type) {
    return switch (type) {
      case RETURN_EXCHANGE -> DefectiveClaimType.REPLENISHMENT;
      case REPAIR -> DefectiveClaimType.REPAIR_COST;
      case CONCESSION -> DefectiveClaimType.QUALITY_DEFECT;
      case SCRAP -> DefectiveClaimType.SCRAP_LOSS;
      case PROMOTION -> DefectiveClaimType.PROMOTION_DEDUCTION;
    };
  }

  /**
   * 处置单状态机的合法流转。
   *
   * <p>与迁移里的 DB 触发器**逐条对齐**（Java 枚举先挡一道，DB 再挡一道）：
   * DRAFT → DECIDED → EXECUTING → COMPLETED，任一非终态可 → CANCELLED。
   */
  public static boolean canTransition(DefectiveOrderStatus from, DefectiveOrderStatus to) {
    if (from == to) {
      return true;
    }
    return switch (from) {
      case DRAFT -> to == DefectiveOrderStatus.DECIDED || to == DefectiveOrderStatus.CANCELLED;
      case DECIDED ->
          to == DefectiveOrderStatus.EXECUTING
              || to == DefectiveOrderStatus.COMPLETED
              || to == DefectiveOrderStatus.CANCELLED;
      case EXECUTING ->
          to == DefectiveOrderStatus.COMPLETED || to == DefectiveOrderStatus.CANCELLED;
      case COMPLETED, CANCELLED -> false;
    };
  }

  private static BigDecimal zero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
