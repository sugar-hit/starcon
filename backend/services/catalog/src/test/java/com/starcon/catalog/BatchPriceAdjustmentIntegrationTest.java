package com.starcon.catalog;

import static com.starcon.catalog.BatchPriceAdjustmentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.catalog.BatchPriceAdjustmentModels.AdjustMode;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentDetail;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentExecuteRequest;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentItem;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentPreview;
import com.starcon.catalog.BatchPriceAdjustmentModels.ItemQuery;
import com.starcon.catalog.BatchPriceAdjustmentModels.ItemStatus;
import com.starcon.catalog.BatchPriceAdjustmentModels.ShopRef;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.4.1.2 批量调价：真实 PostgreSQL 16 + 强制 RLS 下的
 * **预览无副作用 → 确认后落库**、影响范围与反向指标、三种调价方式、跳过规则、
 * 真筛选与真分页、权限分离。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：调价单与明细全部真的落库。
 * 连接池固定为 1 条连接（见底座）：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的七个断言</b>：
 *
 * <ol>
 *   <li>{@link #预览不落库只给影响范围与反向指标()}：蓝图那条「**必须先看预览再执行**」——
 *       预览**一行都不写**（连 5.3.2.1 的应用留痕都不写）。</li>
 *   <li>{@link #执行后调价单与明细落库且与预览逐字一致()}：调价单 + 影响预览报告落库，
 *       而且与预览看到的**逐字一致**（同一份代码算出来的，不可能不一致）。</li>
 *   <li>{@link #改完预计亏损的商品数是反向指标()}：蓝图加粗句要求的那一类数 ——
 *       一批 listing 整体利润上升，也可能其中几个从「小赚」变成「巨亏」。</li>
 *   <li>{@link #固定金额与百分比两种换算按两位小数落定()}：舍入策略钉死。</li>
 *   <li>{@link #按模板方式用定价模板的建议价当新价()}：建议价来自 5.3.2.1，不是本模块编的。</li>
 *   <li>{@link #预览令牌对不上时拒绝执行()}：预览之后价格变了 ⇒ 409，
 *       绝不照着过期的数字改价。</li>
 *   <li>{@link #取不到当前价的listing被跳过而不是拿0顶()}：0 会被当成「免费」。</li>
 * </ol>
 */
class BatchPriceAdjustmentIntegrationTest extends BatchPriceAdjustmentFixture {

  @Autowired BatchPriceAdjustmentService adjustments;
  @Autowired ProfitCalculatorService calculator;

  // ================================================================ 辅助

  /**
   * 预览。
   *
   * <p>🔴 **不套 {@code write(...)}**：预览跑在自己的事务里（它要整体回滚），
   * 所以它不能在别的事务里被调用 —— 这与服务层的显式检查是同一件事。
   */
  private BatchPriceAdjustmentPreview preview(
      AccessService.Access who, BatchPriceAdjustmentRequest request) {
    return adjustments.preview(who, request, bearer(who));
  }

  private BatchPriceAdjustmentDetail execute(
      AccessService.Access who, BatchPriceAdjustmentRequest request, String token) {
    return write(
        who,
        () ->
            adjustments.execute(
                who, new BatchPriceAdjustmentExecuteRequest(request, token), bearer(who)));
  }

  private static BatchPriceAdjustmentItem itemOf(
      List<BatchPriceAdjustmentItem> items, UUID skuId, String siteCode) {
    for (var item : items) {
      if (item.skuId().equals(skuId) && item.siteCode().equals(siteCode)) return item;
    }
    throw new AssertionError("预览里没有这个 listing：" + skuId + " @ " + siteCode);
  }

  /**
   * 某个分类下的调价单行数。
   *
   * <p>🔴 按分类计数（不按整表计数）：同一个租户被本类的多个测试共用，
   * 按整表计数会让断言依赖**测试执行顺序** —— 那是一种假绿/假红。
   */
  private long orderCount(AccessService.Access who, String category) {
    return tx(
            who,
            () ->
                sql.fetchOne(
                        "select count(*) from batch_price_adjustment where scope_category = ?",
                        category)
                    .get(0, Long.class))
        .longValue();
  }

  /** 某个分类下的明细行数。 */
  private long itemCount(AccessService.Access who, String category) {
    return tx(
            who,
            () ->
                sql.fetchOne(
                        "select count(*) from batch_price_adjustment_item i"
                            + " join batch_price_adjustment a"
                            + "   on a.tenant_id = i.tenant_id and a.id = i.adjustment_id"
                            + " where a.scope_category = ?",
                        category)
                    .get(0, Long.class))
        .longValue();
  }

  /** 某条模板的应用留痕行数。 */
  private long templateApplicationCount(AccessService.Access who, UUID templateId) {
    return tx(
            who,
            () ->
                sql.fetchOne(
                        "select count(*) from pricing_template_application where template_id = ?",
                        templateId)
                    .get(0, Long.class))
        .longValue();
  }

  // ================================================================ 断言一：预览无副作用

  /**
   * 预览**不落库**，只给出影响范围与反向指标（蓝图的输出句 + 加粗句）。
   */
  @Test
  void 预览不落库只给影响范围与反向指标() {
    String category = unique("CAT");
    UUID good = pricedSku(actor, "PRE", category, "BrandP");
    UUID loss = pricedSku(actor, "PRE", category, "BrandP");
    priceObservation(actor, good, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    priceObservation(actor, loss, "AMAZON", "US", "USD", "25", null, OffsetDateTime.now());

    var preview = preview(actor, percent(category, "BrandP", "-0.2"));

    // 涉及多少店铺 / 多少商品 / 多少 listing。
    assertThat(preview.shopCount()).isEqualTo(1);
    assertThat(preview.skuCount()).isEqualTo(2);
    assertThat(preview.listingCount()).isEqualTo(2);
    assertThat(preview.willAdjustCount()).isEqualTo(2);
    assertThat(preview.skippedCount()).isZero();

    // 🔴 反向指标：原价 25 的那个改完会亏（20 − 3 平台费 − 5 物流 − 16 成本 = −4）。
    assertThat(preview.lossBeforeCount()).isZero();
    assertThat(preview.lossAfterCount()).isEqualTo(1);
    assertThat(preview.lossAfterSkuCount()).isEqualTo(1);

    // 预计利润变化：100 → 80 ⇒ 毛利 47；25 → 20 ⇒ 毛利 −4；合计 43（原 64.25）。
    assertThat(preview.profitBeforeTotal()).isEqualByComparingTo("64.2500");
    assertThat(preview.profitAfterTotal()).isEqualByComparingTo("43.0000");
    assertThat(preview.profitDeltaTotal()).isEqualByComparingTo("-21.2500");

    // 🔴 **预览一行都不写**：调价单、明细，全是零。
    assertThat(orderCount(actor, category)).isZero();
    assertThat(itemCount(actor, category)).isZero();
  }

  // ================================================================ 断言二：执行后落库

  /** 执行后调价单 + 影响预览报告落库，且与预览**逐字一致**。 */
  @Test
  void 执行后调价单与明细落库且与预览逐字一致() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "EXE", category, "BrandE");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    var preview = preview(actor, fixedAmount(category, "BrandE", "5"));
    var detail = execute(actor, fixedAmount(category, "BrandE", "5"), preview.previewToken());

    // 真的落库了。
    assertThat(orderCount(actor, category)).isEqualTo(1L);
    assertThat(itemCount(actor, category)).isEqualTo(1L);

    // 调价单上的报告快照与预览逐字一致（同一份代码算出来的）。
    assertThat(detail.shopCount()).isEqualTo(preview.shopCount());
    assertThat(detail.skuCount()).isEqualTo(preview.skuCount());
    assertThat(detail.listingCount()).isEqualTo(preview.listingCount());
    assertThat(detail.willAdjustCount()).isEqualTo(preview.willAdjustCount());
    assertThat(detail.skippedCount()).isEqualTo(preview.skippedCount());
    assertThat(detail.lossBeforeCount()).isEqualTo(preview.lossBeforeCount());
    assertThat(detail.lossAfterCount()).isEqualTo(preview.lossAfterCount());
    assertThat(detail.lossAfterSkuCount()).isEqualTo(preview.lossAfterSkuCount());
    assertThat(detail.profitBeforeTotal()).isEqualByComparingTo(preview.profitBeforeTotal());
    assertThat(detail.profitAfterTotal()).isEqualByComparingTo(preview.profitAfterTotal());
    assertThat(detail.profitDeltaTotal()).isEqualByComparingTo(preview.profitDeltaTotal());
    assertThat(detail.previewToken()).isEqualTo(preview.previewToken());
    assertThat(detail.adjustMode()).isEqualTo(AdjustMode.FIXED_AMOUNT);

    // 明细：新旧价与前后利润都在。
    var items =
        tx(
            actor,
            () -> adjustments.listItems(detail.id(), new ItemQuery(null, null, null, null, 0, 20)));
    assertThat(items.items()).hasSize(1);
    var item = items.items().get(0);
    assertThat(item.status()).isEqualTo(ItemStatus.WILL_ADJUST);
    assertThat(item.oldPrice()).isEqualByComparingTo("100.0000");
    assertThat(item.newPrice()).isEqualByComparingTo("105.0000");
    assertThat(item.priceDelta()).isEqualByComparingTo("5.0000");
    assertThat(item.grossProfitBefore()).isEqualByComparingTo("64.0000");
    assertThat(item.grossProfitAfter()).isEqualByComparingTo("68.2500");
    assertThat(item.grossMarginBefore()).isEqualByComparingTo("0.640000");
    assertThat(item.grossMarginAfter()).isEqualByComparingTo("0.650000");
    assertThat(item.lossBefore()).isFalse();
    assertThat(item.lossAfter()).isFalse();
    assertThat(item.priceSource()).isEqualTo("INTERNAL_PRICE");
    assertThat(item.priceObservedAt()).isNotNull();

    // 🔴 与 5.3.1.1 的单次测算**逐字一致** —— 本模块复用它的引擎，不是另写一套算术。
    var single =
        tx(
            actor,
            () ->
                calculator.calculate(
                    new ProfitCalculatorModels.CalculateRequest(
                        skuId,
                        "US",
                        "AMAZON",
                        "USD",
                        "USD",
                        ProfitCalculatorModels.CalcMode.PRICE,
                        new BigDecimal("105"),
                        null,
                        BigDecimal.ONE,
                        null,
                        new BigDecimal("0.15"),
                        null,
                        new BigDecimal("5"),
                        null,
                        List.of(),
                        DAY_OLD.minusDays(1),
                        TODAY,
                        "批量调价测试"),
                    bearer(actor)));
    assertThat(item.grossProfitAfter()).isEqualByComparingTo(single.grossProfit());
    assertThat(item.grossMarginAfter()).isEqualByComparingTo(single.grossMarginRate());
  }

  // ================================================================ 断言三：反向指标

  /**
   * 改完**预计亏损的商品数**是反向指标（蓝图加粗句）。
   *
   * <p>一批 listing 整体利润下降时，运营更该看的是「其中几个从赚变亏」。
   */
  @Test
  void 改完预计亏损的商品数是反向指标() {
    String category = unique("CAT");
    UUID stillGood = pricedSku(actor, "REV", category, "BrandR");
    UUID turnsLoss = pricedSku(actor, "REV", category, "BrandR");
    priceObservation(actor, stillGood, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    priceObservation(actor, turnsLoss, "AMAZON", "US", "USD", "25", null, OffsetDateTime.now());

    var preview = preview(actor, percent(category, "BrandR", "-0.2"));

    // 整体利润是**下降**的（两个都降价了），但关键是「谁变成了亏损」。
    assertThat(preview.profitDeltaTotal()).isLessThan(BigDecimal.ZERO);
    assertThat(preview.lossBeforeCount()).isZero();
    assertThat(preview.lossAfterCount()).isEqualTo(1);
    assertThat(preview.lossAfterSkuCount()).isEqualTo(1);

    var lossItem = itemOf(preview.items(), turnsLoss, "US");
    assertThat(lossItem.lossBefore()).isFalse();
    assertThat(lossItem.lossAfter()).isTrue();
    assertThat(lossItem.grossProfitBefore()).isEqualByComparingTo("0.2500");
    assertThat(lossItem.grossProfitAfter()).isEqualByComparingTo("-4.0000");

    var goodItem = itemOf(preview.items(), stillGood, "US");
    assertThat(goodItem.lossAfter()).isFalse();
  }

  // ================================================================ 断言四：换算与舍入

  /** 固定金额与百分比两种换算按**两位小数 HALF_UP** 落定。 */
  @Test
  void 固定金额与百分比两种换算按两位小数落定() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "RND", category, "BrandN");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "99.99", null, OffsetDateTime.now());

    // 99.99 × 1.1 = 109.989 ⇒ 109.99（2 位 HALF_UP）。
    var percent = preview(actor, percent(category, "BrandN", "0.1"));
    var percentItem = itemOf(percent.items(), skuId, "US");
    assertThat(percentItem.newPrice()).isEqualByComparingTo("109.99");
    assertThat(percentItem.priceDelta()).isEqualByComparingTo("10.0000");

    // 固定金额 +5 ⇒ 104.99。
    var fixed = preview(actor, fixedAmount(category, "BrandN", "5"));
    var fixedItem = itemOf(fixed.items(), skuId, "US");
    assertThat(fixedItem.newPrice()).isEqualByComparingTo("104.99");
    assertThat(fixedItem.priceDelta()).isEqualByComparingTo("5.0000");

    // 降价的负数金额也认。
    var down = preview(actor, fixedAmount(category, "BrandN", "-20"));
    var downItem = itemOf(down.items(), skuId, "US");
    assertThat(downItem.newPrice()).isEqualByComparingTo("79.99");
    assertThat(downItem.priceDelta()).isEqualByComparingTo("-20.0000");
  }

  // ================================================================ 断言五：按模板

  /**
   * 「按模板」方式的新价来自 5.3.2.1 的建议价，**不是本模块编的数字**。
   *
   * <p>固定毛利 25% + 平台费 15% + 物流 5 + 成本 16 ⇒
   * 5.3.1.1 解出的目标毛利价 =（5 + 16）÷（1 − 0.15 − 0.25）= 35。
   */
  @Test
  void 按模板方式用定价模板的建议价当新价() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "TPL", category, "BrandT");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    UUID templateId = fixedMarginTemplate(actor, "批量调价用模板");

    var preview = preview(actor, byTemplate(category, "BrandT", templateId));

    assertThat(preview.willAdjustCount()).isEqualTo(1);
    var item = itemOf(preview.items(), skuId, "US");
    assertThat(item.newPrice()).isEqualByComparingTo("35.0000");
    assertThat(item.priceDelta()).isEqualByComparingTo("-65.0000");
    // 建议价的毛利由 5.3.2.1 的复核给出：35 − 5.25 平台费 − 5 物流 − 16 成本 = 8.75。
    assertThat(item.grossProfitAfter()).isEqualByComparingTo("8.7500");
    assertThat(item.grossMarginAfter()).isEqualByComparingTo("0.250000");

    // 🔴 预览阶段**不留应用留痕**（5.3.2.1 的 apply 会写留痕，预览整体回滚）。
    assertThat(templateApplicationCount(actor, templateId)).isZero();

    // 执行之后才有留痕（apply 在执行路径里真的跑了）。
    var detail = execute(actor, byTemplate(category, "BrandT", templateId), preview.previewToken());
    assertThat(detail.willAdjustCount()).isEqualTo(1);
    assertThat(templateApplicationCount(actor, templateId)).isEqualTo(1L);
  }

  // ================================================================ 断言六：预览令牌

  /** 预览之后价格变了 ⇒ 令牌对不上 ⇒ **拒绝执行**（409），不照着过期的数字改价。 */
  @Test
  void 预览令牌对不上时拒绝执行() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "TOK", category, "BrandK");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    var preview = preview(actor, fixedAmount(category, "BrandK", "5"));

    // 预览之后有人改了价（又来一条更晚的观测）。
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "200", null, OffsetDateTime.now().plusMinutes(1));

    assertThatThrownBy(
            () -> execute(actor, fixedAmount(category, "BrandK", "5"), preview.previewToken()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("预览已失效");

    // 一个字都没落库。
    assertThat(orderCount(actor, category)).isZero();
    assertThat(itemCount(actor, category)).isZero();

    // 重新预览一次（拿到新令牌）就能执行 —— 令牌是内容哈希，内容变了它才变。
    var fresh = preview(actor, fixedAmount(category, "BrandK", "5"));
    assertThat(fresh.previewToken()).isNotEqualTo(preview.previewToken());
    var detail = execute(actor, fixedAmount(category, "BrandK", "5"), fresh.previewToken());
    assertThat(detail.willAdjustCount()).isEqualTo(1);
    var item =
        tx(
                actor,
                () ->
                    adjustments.listItems(
                        detail.id(), new ItemQuery(null, null, null, null, 0, 20)))
            .items()
            .get(0);
    // 新价按**新的**当前价（200）算：200 + 5 = 205。
    assertThat(item.oldPrice()).isEqualByComparingTo("200.0000");
    assertThat(item.newPrice()).isEqualByComparingTo("205.0000");
  }

  /** 同一个输入的预览令牌必须**稳定**（不含随机量与时间戳）。 */
  @Test
  void 同一个输入的预览令牌稳定() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "STB", category, "BrandS");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    var first = preview(actor, fixedAmount(category, "BrandS", "5"));
    var second = preview(actor, fixedAmount(category, "BrandS", "5"));
    assertThat(second.previewToken()).isEqualTo(first.previewToken());
  }

  // ================================================================ 断言七：跳过规则

  /** 取不到当前价的 listing 被**跳过**，而不是拿 0 顶。 */
  @Test
  void 取不到当前价的listing被跳过而不是拿0顶() {
    String category = unique("CAT");
    UUID withPrice = pricedSku(actor, "SKP", category, "BrandX");
    UUID noPrice = pricedSku(actor, "SKP", category, "BrandX");
    priceObservation(actor, withPrice, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    var preview = preview(actor, percent(category, "BrandX", "0.1"));

    assertThat(preview.listingCount()).isEqualTo(2);
    assertThat(preview.willAdjustCount()).isEqualTo(1);
    assertThat(preview.skippedCount()).isEqualTo(1);

    var skipped = itemOf(preview.items(), noPrice, "US");
    assertThat(skipped.status()).isEqualTo(ItemStatus.SKIPPED);
    assertThat(skipped.oldPrice()).isNull();
    assertThat(skipped.newPrice()).isNull();
    assertThat(skipped.grossProfitBefore()).isNull();
    assertThat(skipped.grossProfitAfter()).isNull();
    assertThat(skipped.lossBefore()).isNull();
    assertThat(skipped.lossAfter()).isNull();
    assertThat(skipped.skipReason()).contains("没有价格观测");
  }

  /** 调完不是正价 ⇒ 跳过这一行（绝不改成 0）。 */
  @Test
  void 调完不是正价时跳过这一行() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "NEG", category, "BrandN");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "10", null, OffsetDateTime.now());

    var preview = preview(actor, fixedAmount(category, "BrandN", "-20"));

    assertThat(preview.willAdjustCount()).isZero();
    assertThat(preview.skippedCount()).isEqualTo(1);
    var item = itemOf(preview.items(), skuId, "US");
    assertThat(item.status()).isEqualTo(ItemStatus.SKIPPED);
    assertThat(item.newPrice()).isNull();
    assertThat(item.oldPrice()).isEqualByComparingTo("10.0000");
    assertThat(item.skipReason()).contains("不是正价");
  }

  /** 币种不同 ⇒ 跳过（绝不按 1:1 折算）。 */
  @Test
  void 币种不同的listing被跳过而不是按1比1折算() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "CUR", category, "BrandC");
    priceObservation(actor, skuId, "AMAZON", "US", "EUR", "100", null, OffsetDateTime.now());

    var preview = preview(actor, percent(category, "BrandC", "0.1"));

    assertThat(preview.willAdjustCount()).isZero();
    var item = itemOf(preview.items(), skuId, "US");
    assertThat(item.status()).isEqualTo(ItemStatus.SKIPPED);
    assertThat(item.skipReason()).contains("币种");
    assertThat(item.currency()).isEqualTo("EUR");
  }

  // ================================================================ 多店铺

  /** 涉及多少店铺 —— 目标集合可以跨店铺，预览报告把它报出来。 */
  @Test
  void 目标集合可以跨店铺并报出涉及多少店铺() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "SHP", category, "BrandH");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    priceObservation(actor, skuId, "AMAZON", "CA", "USD", "80", null, OffsetDateTime.now());

    var preview =
        preview(
            actor,
            percentInShops(
                category,
                "BrandH",
                "0.1",
                List.of(new ShopRef("AMAZON", "US"), new ShopRef("AMAZON", "CA"))));

    assertThat(preview.shopCount()).isEqualTo(2);
    assertThat(preview.skuCount()).isEqualTo(1);
    assertThat(preview.listingCount()).isEqualTo(2);
    assertThat(preview.willAdjustCount()).isEqualTo(2);
    assertThat(itemOf(preview.items(), skuId, "US").newPrice()).isEqualByComparingTo("110.00");
    assertThat(itemOf(preview.items(), skuId, "CA").newPrice()).isEqualByComparingTo("88.00");
  }

  // ================================================================ 明细筛选

  /** 明细列表支持真筛选与真分页（筛选在数据库层）。 */
  @Test
  void 明细列表支持真筛选与真分页() {
    String category = unique("CAT");
    UUID ok = pricedSku(actor, "FLT", category, "BrandF");
    UUID loss = pricedSku(actor, "FLT", category, "BrandF");
    UUID noPrice = pricedSku(actor, "FLT", category, "BrandF");
    priceObservation(actor, ok, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    priceObservation(actor, loss, "AMAZON", "US", "USD", "25", null, OffsetDateTime.now());

    var preview = preview(actor, percent(category, "BrandF", "-0.2"));
    var detail = execute(actor, percent(category, "BrandF", "-0.2"), preview.previewToken());

    // 只看会亏的。
    var lossOnly =
        tx(
            actor,
            () ->
                adjustments.listItems(
                    detail.id(), new ItemQuery(null, "LOSS_AFTER", null, null, 0, 20)));
    assertThat(lossOnly.total()).isEqualTo(1L);
    assertThat(lossOnly.items().get(0).skuId()).isEqualTo(loss);

    // 只看跳过的。
    var skippedOnly =
        tx(
            actor,
            () ->
                adjustments.listItems(
                    detail.id(), new ItemQuery("SKIPPED", null, null, null, 0, 20)));
    assertThat(skippedOnly.total()).isEqualTo(1L);
    assertThat(skippedOnly.items().get(0).skuId()).isEqualTo(noPrice);

    // 关键字。
    var byKeyword =
        tx(
            actor,
            () ->
                adjustments.listItems(
                    detail.id(), new ItemQuery(null, null, "FLT", null, 0, 20)));
    assertThat(byKeyword.total()).isEqualTo(3L);

    // 真分页。
    var page1 =
        tx(
            actor,
            () -> adjustments.listItems(detail.id(), new ItemQuery(null, null, null, null, 0, 2)));
    var page2 =
        tx(
            actor,
            () -> adjustments.listItems(detail.id(), new ItemQuery(null, null, null, null, 1, 2)));
    assertThat(page1.total()).isEqualTo(3L);
    assertThat(page1.items()).hasSize(2);
    assertThat(page2.items()).hasSize(1);
    assertThat(page1.items().get(0).id()).isNotEqualTo(page2.items().get(0).id());

    // 调价单列表也分页。
    var orders = tx(actor, () -> adjustments.listAdjustments(0, 10));
    assertThat(orders.items()).isNotEmpty();
    assertThat(orders.total()).isGreaterThan(0L);
  }

  // ================================================================ HTTP 端点

  /** HTTP 端点通：预览 → 执行 → 明细。 */
  @Test
  void http端点通() throws Exception {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "API", category, "BrandA");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    var request = fixedAmount(category, "BrandA", "5");
    var previewBody =
        http.perform(
                post("/catalog/batch-price-adjustments/preview")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(JSON.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.willAdjustCount").value(1))
            .andExpect(jsonPath("$.shopCount").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var token = JSON.readTree(previewBody).get("previewToken").asText();
    assertThat(token).isNotBlank();

    var executeBody =
        http.perform(
                post("/catalog/batch-price-adjustments")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        JSON.writeValueAsString(
                            new BatchPriceAdjustmentExecuteRequest(request, token))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.willAdjustCount").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var detail = JSON.readTree(executeBody).get("id").asText();

    http.perform(
            get("/catalog/batch-price-adjustments/" + detail + "/items")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    http.perform(get("/catalog/batch-price-adjustments/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.previewGateNote").exists());
  }

  /** 只读角色不能发起批量调价（预览与执行都要写权限）。 */
  @Test
  void 只读角色不能发起批量调价() throws Exception {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "RO", category, "BrandR");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    http.perform(
            post("/catalog/batch-price-adjustments/preview")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(JSON.writeValueAsString(percent(category, "BrandR", "0.1"))))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 时间

  /** 调价单创建时刻写回不偏 28800s（timestamptz 用 OffsetDateTime 绑定）。 */
  @Test
  void 调价单创建时刻写回零漂移() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "TS", category, "BrandT");
    var observedAt = OffsetDateTime.now().minusHours(3);
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "100", null, observedAt);

    var preview = preview(actor, fixedAmount(category, "BrandT", "5"));
    var before = OffsetDateTime.now().minusSeconds(5);
    var detail = execute(actor, fixedAmount(category, "BrandT", "5"), preview.previewToken());
    var stored =
        tx(
            actor,
            () ->
                sql.fetchOne(
                        "select created_at from batch_price_adjustment where id=?", detail.id())
                    .get("created_at", OffsetDateTime.class));
    assertThat(stored.toInstant()).isAfterOrEqualTo(before.toInstant());
    assertThat(stored.toInstant()).isBefore(OffsetDateTime.now().plusSeconds(5).toInstant());

    // 价格观测时刻读回来**逐字一致**（timestamptz 不偏）。
    var item =
        tx(
                actor,
                () ->
                    adjustments.listItems(
                        detail.id(), new ItemQuery(null, null, null, null, 0, 20)))
            .items()
            .get(0);
    assertThat(item.priceObservedAt().toInstant()).isEqualTo(observedAt.toInstant());
  }
}
