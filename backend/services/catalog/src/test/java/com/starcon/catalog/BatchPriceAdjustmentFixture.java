package com.starcon.catalog;

import static com.starcon.catalog.PricingTemplateModels.*;

import com.starcon.catalog.BatchPriceAdjustmentModels.AdjustMode;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentRequest;
import com.starcon.catalog.BatchPriceAdjustmentModels.ShopRef;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 5.4.1.2 批量调价的**共享底座**。
 *
 * <p>🔴 本底座**继承** 5.3.1.2 的 {@link BatchCalcFixture}（两个跨服务替身 + 业务夹具 +
 * 单连接连接池），**不改它一个字符** —— 底座是共享资产，改它会让兄弟模块的测试一起红。
 * 本类只加本模块自己需要的东西：一个「按模板」方式用的定价模板。
 *
 * <p><b>为什么单连接</b>：连接池固定为 1 条连接（见底座）——
 * 只有复用同一条物理连接，「归还后不串租户」才是真的被验证过。
 */
abstract class BatchPriceAdjustmentFixture extends BatchCalcFixture {

  @Autowired PricingTemplateService templates;

  // ================================================================ 请求构造

  /** 单店铺（AMAZON / US）的一次批量调价请求。 */
  BatchPriceAdjustmentRequest request(
      AdjustMode mode, String category, String brand, BigDecimal amount, BigDecimal rate) {
    return new BatchPriceAdjustmentRequest(
        mode,
        amount,
        rate,
        null,
        "USD",
        "USD",
        null,
        List.of(new ShopRef("AMAZON", "US")),
        List.of(),
        category,
        brand,
        BigDecimal.ONE,
        new BigDecimal("0.15"),
        null,
        new BigDecimal("5"),
        null,
        DAY_OLD.minusDays(1),
        TODAY,
        null,
        null,
        null,
        "批量调价测试");
  }

  /** 固定金额方式（+5）。 */
  BatchPriceAdjustmentRequest fixedAmount(String category, String brand, String amount) {
    return request(AdjustMode.FIXED_AMOUNT, category, brand, new BigDecimal(amount), null);
  }

  /** 百分比方式（rate 是小数口径）。 */
  BatchPriceAdjustmentRequest percent(String category, String brand, String rate) {
    return request(AdjustMode.PERCENT, category, brand, null, new BigDecimal(rate));
  }

  /** 多店铺的请求（涉及多少店铺 —— 预览报告里的一个数）。 */
  BatchPriceAdjustmentRequest percentInShops(
      String category, String brand, String rate, List<ShopRef> shops) {
    var base = percent(category, brand, rate);
    return new BatchPriceAdjustmentRequest(
        base.adjustMode(),
        base.adjustAmount(),
        base.adjustRate(),
        base.templateId(),
        base.currency(),
        base.costCurrency(),
        base.fxRate(),
        shops,
        base.skuIds(),
        base.category(),
        base.brand(),
        base.quantity(),
        base.platformFeeRate(),
        base.platformFeeFixed(),
        base.logisticsCostPerUnit(),
        base.monthlyFixedCost(),
        base.costWindowFrom(),
        base.costWindowTo(),
        base.daysOfCover(),
        base.competitorReferencePrice(),
        base.competitorCurrency(),
        base.note());
  }

  /** 按模板方式的请求（模板的建议价就是新价）。 */
  BatchPriceAdjustmentRequest byTemplate(String category, String brand, UUID templateId) {
    var base = request(AdjustMode.TEMPLATE, category, brand, null, null);
    return new BatchPriceAdjustmentRequest(
        base.adjustMode(),
        base.adjustAmount(),
        base.adjustRate(),
        templateId,
        base.currency(),
        base.costCurrency(),
        base.fxRate(),
        base.shops(),
        base.skuIds(),
        base.category(),
        base.brand(),
        base.quantity(),
        // 平台费率与物流单价**不给** ⇒ 走模板上的口径（这正是「按模板」的语义）。
        null,
        null,
        null,
        base.monthlyFixedCost(),
        base.costWindowFrom(),
        base.costWindowTo(),
        base.daysOfCover(),
        base.competitorReferencePrice(),
        base.competitorCurrency(),
        base.note());
  }

  // ================================================================ 定价模板（5.3.2.1）

  /**
   * 一条「固定毛利 25%」的定价模板。
   *
   * <p>🔴 商品阶段必须是 NEW —— 夹具里的 SPU 是 DRAFT（未上架），
   * 而 5.3.2.1 规定「新品与老品用不同模板」，阶段不符会 409。
   */
  UUID fixedMarginTemplate(AccessService.Access who, String name) {
    var input =
        new TemplateInput(
            unique("TPL"),
            name,
            "固定毛利 25%",
            BaseMethod.FIXED_MARGIN,
            new BigDecimal("0.25"),
            null,
            null,
            ProductStage.NEW,
            "USD",
            new BigDecimal("0.15"),
            null,
            new BigDecimal("5"),
            "USD",
            null,
            true,
            OffsetDateTime.now().minusDays(1),
            null,
            "夹具模板",
            List.of(),
            List.of(),
            null);
    var saved = write(who, () -> templates.save(who, null, input));
    return saved.summary().id();
  }
}
