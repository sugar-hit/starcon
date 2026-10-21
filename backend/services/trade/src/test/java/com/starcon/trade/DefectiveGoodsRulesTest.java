package com.starcon.trade;

import static com.starcon.trade.DefectiveGoodsModels.DefectiveClaimType;
import static com.starcon.trade.DefectiveGoodsModels.DefectiveDispositionType;
import static com.starcon.trade.DefectiveGoodsModels.DefectiveInventoryAction;
import static com.starcon.trade.DefectiveGoodsModels.DefectiveOrderStatus;
import static com.starcon.trade.DefectiveGoodsRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 6.4.2.2 的纯规则复验。
 *
 * <p>这几条规则是本模块最容易被「顺手改一下」弄坏的地方（可售量上限怎么算、
 * 放行量怎么来、索赔类型怎么推导、状态怎么流转），而它们<b>全都不需要数据库</b> ——
 * 所以放在一个不依赖 Testcontainers 的单元测试里钉住：服务层与数据库层各挡一道之外，
 * 规则本身还有第三道。
 */
class DefectiveGoodsRulesTest {

  @Test
  void 可售量上限等于合格量加让步接收量() {
    // 与 6.4.2.1 质检单上的 sellableQuantity 同一口径：让步接收量是可售的。
    assertThat(sellableCeiling(new BigDecimal("70"), new BigDecimal("10")))
        .isEqualByComparingTo(new BigDecimal("80"));
    assertThat(sellableCeiling(BigDecimal.ZERO, BigDecimal.ZERO))
        .isEqualByComparingTo(BigDecimal.ZERO);
    // null 不能变成 NPE —— 「没登记」与「零」在汇总里要分开处理。
    assertThat(sellableCeiling(null, new BigDecimal("3")))
        .isEqualByComparingTo(new BigDecimal("3"));
  }

  @Test
  void 可处置量等于不合格量减去已处置量() {
    assertThat(remainingDefective(new BigDecimal("20"), BigDecimal.ZERO))
        .isEqualByComparingTo(new BigDecimal("20"));
    assertThat(remainingDefective(new BigDecimal("20"), new BigDecimal("8")))
        .isEqualByComparingTo(new BigDecimal("12"));
    // 超额处置时不能给出负数 —— 负数会让「还能处置多少」变成看不懂的数。
    assertThat(remainingDefective(new BigDecimal("20"), new BigDecimal("25")))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void 放行量只由处置类型决定() {
    // 让步接收与转促销品整行回可售池。
    assertThat(releaseFor(DefectiveDispositionType.CONCESSION, new BigDecimal("30")))
        .isEqualByComparingTo(new BigDecimal("30"));
    assertThat(releaseFor(DefectiveDispositionType.PROMOTION, new BigDecimal("12.5")))
        .isEqualByComparingTo(new BigDecimal("12.5"));
    // 报废 / 退换货 / 返修的货离仓或不可售。
    assertThat(releaseFor(DefectiveDispositionType.SCRAP, new BigDecimal("30")))
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(releaseFor(DefectiveDispositionType.RETURN_EXCHANGE, new BigDecimal("30")))
        .isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(releaseFor(DefectiveDispositionType.REPAIR, new BigDecimal("30")))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void 处置类型映射到库存动作与索赔类型() {
    assertThat(actionFor(DefectiveDispositionType.SCRAP))
        .isEqualTo(DefectiveInventoryAction.SCRAP_OUT);
    assertThat(actionFor(DefectiveDispositionType.RETURN_EXCHANGE))
        .isEqualTo(DefectiveInventoryAction.RETURN_OUT);
    assertThat(actionFor(DefectiveDispositionType.REPAIR))
        .isEqualTo(DefectiveInventoryAction.REPAIR_HOLD);
    assertThat(actionFor(DefectiveDispositionType.CONCESSION))
        .isEqualTo(DefectiveInventoryAction.CONCESSION_RELEASE);
    assertThat(actionFor(DefectiveDispositionType.PROMOTION))
        .isEqualTo(DefectiveInventoryAction.PROMOTION_RELEASE);

    assertThat(claimTypeFor(DefectiveDispositionType.CONCESSION))
        .isEqualTo(DefectiveClaimType.QUALITY_DEFECT);
    assertThat(claimTypeFor(DefectiveDispositionType.RETURN_EXCHANGE))
        .isEqualTo(DefectiveClaimType.REPLENISHMENT);
    assertThat(claimTypeFor(DefectiveDispositionType.REPAIR))
        .isEqualTo(DefectiveClaimType.REPAIR_COST);
    assertThat(claimTypeFor(DefectiveDispositionType.SCRAP))
        .isEqualTo(DefectiveClaimType.SCRAP_LOSS);
    assertThat(claimTypeFor(DefectiveDispositionType.PROMOTION))
        .isEqualTo(DefectiveClaimType.PROMOTION_DEDUCTION);
  }

  @Test
  void 状态机只允许合法流转() {
    assertThat(canTransition(DefectiveOrderStatus.DRAFT, DefectiveOrderStatus.DECIDED)).isTrue();
    assertThat(canTransition(DefectiveOrderStatus.DRAFT, DefectiveOrderStatus.CANCELLED)).isTrue();
    assertThat(canTransition(DefectiveOrderStatus.DECIDED, DefectiveOrderStatus.EXECUTING))
        .isTrue();
    assertThat(canTransition(DefectiveOrderStatus.DECIDED, DefectiveOrderStatus.COMPLETED))
        .isTrue();
    assertThat(canTransition(DefectiveOrderStatus.EXECUTING, DefectiveOrderStatus.COMPLETED))
        .isTrue();
    // 状态不变也算合法（明细行变更触发的重算 UPDATE 保持 DRAFT）。
    assertThat(canTransition(DefectiveOrderStatus.DRAFT, DefectiveOrderStatus.DRAFT)).isTrue();

    // 不允许：草稿直接完成、已决策退回草稿、终态复活。
    assertThat(canTransition(DefectiveOrderStatus.DRAFT, DefectiveOrderStatus.COMPLETED)).isFalse();
    assertThat(canTransition(DefectiveOrderStatus.DECIDED, DefectiveOrderStatus.DRAFT)).isFalse();
    assertThat(canTransition(DefectiveOrderStatus.COMPLETED, DefectiveOrderStatus.EXECUTING))
        .isFalse();
    assertThat(canTransition(DefectiveOrderStatus.CANCELLED, DefectiveOrderStatus.DRAFT)).isFalse();
  }

  @Test
  void 数量与金额刻度固定() {
    assertThat(scale(new BigDecimal("1.2345678")).scale()).isEqualTo(6);
    assertThat(scale(new BigDecimal("1.2345678")).toPlainString()).isEqualTo("1.234568");
    assertThat(scaleAmount(new BigDecimal("12.34567")).scale()).isEqualTo(4);
    assertThat(scaleAmount(new BigDecimal("12.34567")).toPlainString()).isEqualTo("12.3457");
    assertThat(scale(null)).isNull();
    assertThat(scaleAmount(null)).isNull();
  }
}
