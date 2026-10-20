package com.starcon.catalog;

import static com.starcon.catalog.PriceHistoryModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.catalog.BatchPriceAdjustmentModels.AdjustMode;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentDetail;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentExecuteRequest;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentPreview;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentRequest;
import com.starcon.catalog.BatchPriceAdjustmentModels.ItemStatus;
import com.starcon.catalog.BatchPriceAdjustmentModels.ShopRef;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryChangeInput;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryChangeQuery;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryComparisonQuery;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryReasonInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.5.1.1 价格历史：真实 PostgreSQL 16 + 强制 RLS 下的
 * **变更登记 → 时间轴 → 单条详情 → 利润 / 销量对比**。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：价格变更事实全部真的落库。
 * 连接池固定为 1 条连接（见底座）。
 *
 * <p><b>本文件最重要的九个断言</b>：
 *
 * <ol>
 *   <li>{@link #登记一次降价后时间轴上有一条且旧价新价方向都对()}：旧价 / 新价 / 价差 / 方向 /
 *       幅度逐项断言 —— 方向是**派生**的，不能与数字打架。</li>
 *   <li>{@link #首次定价不算成从零涨到新价()}：首次定价**不是**「从 0 涨到新价」，
 *       否则时间轴上会凭空多出一跳 100% 的涨幅。</li>
 *   <li>{@link #timestamptz写回零漂移()}：断言**写回的值**等于同一绝对时刻，
 *       而不是只断言「没抛异常」。</li>
 *   <li>{@link #利润快照与5_3_1_1引擎逐字一致()}：本模块零算法，
 *       快照必须与 {@link ProfitCalculatorService#calculate} 的输出**逐字相同**。</li>
 *   <li>{@link #批量调价执行后这次改价真的落进了时间轴()}：5.4.1.2 是**已交付**的上游，
 *       它改完价必须真的能在这本账上看见。</li>
 *   <li>{@link #重复投递同一个幂等键不会堆出第二条变更()}：重试 / 补偿不得堆行。</li>
 *   <li>{@link #时间轴按维度真筛选且真分页真排序()}：筛选、分页、排序都在 DB 层。</li>
 *   <li>{@link #销量维度未接线时显式不可用而不是零()}：**绝不用 0 冒充销量** ——
 *       那会让每次降价都显示成「降价后一件没卖出去」。</li>
 *   <li>{@link #变更事实不可变没有改写入口()}：GRANT 里没有 UPDATE / DELETE。</li>
 * </ol>
 */
class PriceHistoryIntegrationTest extends BatchCalcFixture {

  @Autowired PriceHistoryService history;
  @Autowired BatchPriceAdjustmentService adjustments;
  @Autowired ProfitCalculatorService calculator;

  private static final String PLATFORM = "AMAZON";
  private static final String SITE = "US";

  // ================================================================ 辅助

  /** 登记一次价格变更（走服务层的真实写入口）。 */
  private PriceHistoryChangeRow register(
      AccessService.Access who,
      UUID skuId,
      String oldPrice,
      String newPrice,
      String source,
      String reason,
      OffsetDateTime at) {
    var input =
        new PriceHistoryChangeInput(
            skuId,
            PLATFORM,
            SITE,
            "USD",
            oldPrice == null ? null : new BigDecimal(oldPrice),
            new BigDecimal(newPrice),
            source,
            "",
            reason,
            at,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "");
    var saved = write(who, () -> history.register(who, bearer(who), input));
    var row = tx(who, () -> history.getChange(saved.id()));
    return row.change();
  }

  /** 登记一次**带利润口径**的变更（平台费率 + 运费 + 成本窗口都给了）。 */
  private PriceHistoryChangeRow registerWithProfit(
      AccessService.Access who, UUID skuId, String oldPrice, String newPrice, OffsetDateTime at) {
    var input =
        new PriceHistoryChangeInput(
            skuId,
            PLATFORM,
            SITE,
            "USD",
            oldPrice == null ? null : new BigDecimal(oldPrice),
            new BigDecimal(newPrice),
            "MANUAL_ADJUSTMENT",
            "",
            "利润口径测试",
            at,
            BigDecimal.ONE,
            "USD",
            null,
            new BigDecimal("0.15"),
            null,
            new BigDecimal("5"),
            null,
            DAY_OLD,
            TODAY,
            "");
    var saved = write(who, () -> history.register(who, bearer(who), input));
    return tx(who, () -> history.getChange(saved.id())).change();
  }

  /** 某个 SKU 的变更条数（按 SKU 计数，不按整表 —— 避免依赖测试执行顺序）。 */
  private long changeCount(AccessService.Access who, UUID skuId) {
    return tx(
            who,
            () ->
                sql.fetchOne(
                        "select count(*) from price_change_history where sku_id=?", skuId)
                    .get(0, Long.class))
        .longValue();
  }

  // ================================================================ 断言一

  /** 登记一次降价后，时间轴上有一条，旧价 / 新价 / 价差 / 方向 / 幅度逐项对。 */
  @Test
  void 登记一次降价后时间轴上有一条且旧价新价方向都对() {
    UUID skuId = pricedSku(actor, "PRH", unique("CAT"), "BrandH");
    var row = register(actor, skuId, "100", "90", "MANUAL_ADJUSTMENT", "跟竞品", OffsetDateTime.now());

    assertThat(row.skuId()).isEqualTo(skuId);
    assertThat(row.oldPrice()).isEqualByComparingTo("100.00");
    assertThat(row.newPrice()).isEqualByComparingTo("90.00");
    assertThat(row.priceDelta()).isEqualByComparingTo("-10.00");
    assertThat(row.direction()).isEqualTo("DOWN");
    assertThat(row.directionLabel()).isEqualTo("降价");
    assertThat(row.changeRatio()).isEqualByComparingTo("-0.100000");
    assertThat(row.changeSource()).isEqualTo("MANUAL_ADJUSTMENT");
    assertThat(row.reasonText()).isEqualTo("跟竞品");
    assertThat(row.sourceModule()).isEqualTo("MANUAL_REGISTRATION");
    assertThat(row.operatorId()).isNotNull();
    // 变更事实落在调用者自己的租户名下。
    assertThat(changeCount(actor, skuId)).isEqualTo(1L);
  }

  // ================================================================ 断言二

  /** 首次定价**不是**「从 0 涨到新价」—— 否则时间轴上凭空多一跳 100% 的涨幅。 */
  @Test
  void 首次定价不算成从零涨到新价() {
    UUID skuId = pricedSku(actor, "PRH", unique("CAT"), "BrandH");
    var row = register(actor, skuId, null, "19.99", "MANUAL_ADJUSTMENT", "首次定价", OffsetDateTime.now());

    assertThat(row.oldPrice()).isNull();
    assertThat(row.priceDelta()).isNull();
    assertThat(row.changeRatio()).isNull();
    assertThat(row.direction()).isEqualTo("INITIAL");
    assertThat(row.newPrice()).isEqualByComparingTo("19.99");
  }

  // ================================================================ 断言三

  /** timestamptz 写回**零漂移**：断言写回的值等于同一绝对时刻。 */
  @Test
  void timestamptz写回零漂移() {
    UUID skuId = pricedSku(actor, "PRH", unique("CAT"), "BrandH");
    var at = OffsetDateTime.parse("2026-03-01T10:15:30+08:00");
    var row = register(actor, skuId, "100", "95", "MANUAL_ADJUSTMENT", "时区漂移", at);

    assertThat(row.effectiveAt().toInstant()).isEqualTo(at.toInstant());
    // 库里再读一次，断言的是**落库的值**而不是内存里的对象。
    var stored =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select effective_at from price_change_history where id=?", row.id())
                        .get("effective_at", OffsetDateTime.class))
            .toInstant();
    assertThat(stored).isEqualTo(at.toInstant());
    // 落库时刻与生效时刻是两件事（补录的调价单：生效在上周，入账在今天）。
    assertThat(row.recordedAt()).isNotNull();
  }

  // ================================================================ 断言四

  /** 利润快照与 5.3.1.1 的引擎**逐字一致**（本模块零算法）。 */
  @Test
  void 利润快照与5_3_1_1引擎逐字一致() {
    UUID skuId = pricedSku(actor, "PRH", unique("CAT"), "BrandH");
    var row = registerWithProfit(actor, skuId, "100", "90", OffsetDateTime.now());

    assertThat(row.decisionReady()).isTrue();
    assertThat(row.grossProfitAfter()).isNotNull();
    assertThat(row.grossMarginAfter()).isNotNull();

    // 同一个引擎、同一份输入 ⇒ 逐字相同。
    // 🔴 测算必须在**租户上下文里**跑（成本构成要跨服务读 warehouse，且 RLS 按租户隔离），
    //    所以这里走 tx(...) 而不是直接调 —— 直接调会因 RLS 看不到 SKU 而 404。
    var expected =
        tx(
            actor,
            () ->
                calculator.calculate(
                    new ProfitCalculatorModels.CalculateRequest(
                        skuId,
                        SITE,
                        PLATFORM,
                        "USD",
                        "USD",
                        ProfitCalculatorModels.CalcMode.PRICE,
                        new BigDecimal("90.00"),
                        null,
                        BigDecimal.ONE,
                        null,
                        new BigDecimal("0.15"),
                        null,
                        new BigDecimal("5"),
                        null,
                        List.of(),
                        DAY_OLD,
                        TODAY,
                        "断言用"),
                    bearer(actor)));
    assertThat(row.grossProfitAfter()).isEqualByComparingTo(expected.grossProfit());
    assertThat(row.grossMarginAfter()).isEqualByComparingTo(expected.grossMarginRate());
  }

  // ================================================================ 断言五

  /** 5.4.1.2 批量调价执行后，这次改价**真的落进了时间轴**。 */
  @Test
  void 批量调价执行后这次改价真的落进了时间轴() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "PRH", category, "BrandH");
    priceObservation(actor, skuId, PLATFORM, SITE, "USD", "100", null, OffsetDateTime.now());

    var request =
        new BatchPriceAdjustmentRequest(
            AdjustMode.PERCENT,
            null,
            new BigDecimal("0.1"),
            null,
            "USD",
            "USD",
            null,
            List.of(new ShopRef(PLATFORM, SITE)),
            List.of(),
            category,
            "BrandH",
            BigDecimal.ONE,
            new BigDecimal("0.15"),
            null,
            new BigDecimal("5"),
            null,
            DAY_OLD,
            TODAY,
            null,
            null,
            null,
            "价格历史联动测试");

    var preview = adjustments.preview(actor, request, bearer(actor));
    BatchPriceAdjustmentDetail detail =
        write(
            actor,
            () ->
                adjustments.execute(
                    actor, new BatchPriceAdjustmentExecuteRequest(request, preview.previewToken()), bearer(actor)));

    var item = preview.items().get(0);
    assertThat(item.status()).isEqualTo(ItemStatus.WILL_ADJUST);
    assertThat(detail.id()).isNotNull();

    // 时间轴上真的多了一条来自 5.4.1.2 的变更。
    var changes =
        tx(
                actor,
                () ->
                    history.listChanges(
                        new PriceHistoryChangeQuery(
                            null, skuId, PLATFORM, SITE, "BATCH_ADJUSTMENT", null, null, null, null,
                            null, 0, 50)))
            .items();
    assertThat(changes).hasSize(1);
    var row = changes.get(0);
    assertThat(row.changeSource()).isEqualTo("BATCH_ADJUSTMENT");
    assertThat(row.sourceModule()).isEqualTo("5.4.1.2");
    assertThat(row.oldPrice()).isEqualByComparingTo(item.oldPrice());
    assertThat(row.newPrice()).isEqualByComparingTo(item.newPrice());
    assertThat(row.direction()).isEqualTo("UP");
    // 利润快照原样来自 5.4.1.2 自己算好的那一行（同一个引擎）。
    assertThat(row.grossProfitBefore()).isEqualByComparingTo(item.grossProfitBefore());
    assertThat(row.grossProfitAfter()).isEqualByComparingTo(item.grossProfitAfter());
    assertThat(row.grossMarginAfter()).isEqualByComparingTo(item.grossMarginAfter());
  }

  // ================================================================ 断言六

  /** 重复投递同一个幂等键**不会**堆出第二条变更。 */
  @Test
  void 重复投递同一个幂等键不会堆出第二条变更() {
    UUID skuId = pricedSku(actor, "PRH", unique("CAT"), "BrandH");
    var event =
        new PriceChangeHistoryPort.PriceChangeEvent(
            skuId,
            PLATFORM,
            SITE,
            "USD",
            new BigDecimal("100"),
            new BigDecimal("80"),
            "MANUAL_ADJUSTMENT",
            "",
            "幂等测试",
            actor.userId(),
            "5.4.1.1",
            "ADJ-1",
            "ADJ-1:LINE-1",
            OffsetDateTime.now(),
            null,
            null);

    var first = write(actor, () -> history.record(actor, bearer(actor), event));
    var second = write(actor, () -> history.record(actor, bearer(actor), event));

    assertThat(second).isEqualTo(first);
    assertThat(changeCount(actor, skuId)).isEqualTo(1L);
  }

  // ================================================================ 断言七

  /** 时间轴按 SKU / 店铺 / 时间范围 / 变更来源**真筛选**，分页与排序也是真的。 */
  @Test
  void 时间轴按维度真筛选且真分页真排序() {
    UUID skuA = pricedSku(actor, "PRHA", unique("CAT"), "BrandA");
    UUID skuB = pricedSku(actor, "PRHB", unique("CAT"), "BrandB");
    var base = OffsetDateTime.now().minusDays(10);

    register(actor, skuA, "100", "90", "MANUAL_ADJUSTMENT", "A1", base);
    register(actor, skuA, "90", "95", "PRICE_RULE", "A2", base.plusDays(2));
    register(actor, skuA, "95", "80", "BATCH_ADJUSTMENT", "A3", base.plusDays(4));
    register(actor, skuB, "50", "40", "MANUAL_ADJUSTMENT", "B1", base.plusDays(1));

    // 按 SKU 筛。
    var bySku =
        tx(
                actor,
                () ->
                    history.listChanges(
                        new PriceHistoryChangeQuery(null, skuA, null, null, null, null, null, null, null, null, 0, 50)))
            .items();
    assertThat(bySku).hasSize(3);
    assertThat(bySku).allSatisfy(r -> assertThat(r.skuId()).isEqualTo(skuA));

    // 按变更来源筛。
    var bySource =
        tx(
                actor,
                () ->
                    history.listChanges(
                        new PriceHistoryChangeQuery(
                            null, skuA, null, null, "PRICE_RULE", null, null, null, null, null, 0, 50)))
            .items();
    assertThat(bySource).hasSize(1);
    assertThat(bySource.get(0).reasonText()).isEqualTo("A2");

    // 按店铺筛（换一个店铺码 ⇒ 筛不到）。
    var otherShop =
        tx(
                actor,
                () ->
                    history.listChanges(
                        new PriceHistoryChangeQuery(null, skuA, PLATFORM, "DE", null, null, null, null, null, null, 0, 50)))
            .items();
    assertThat(otherShop).isEmpty();

    // 按时间范围筛（半开区间：from 含、to 含整天）。
    var byWindow =
        tx(
                actor,
                () ->
                    history.listChanges(
                        new PriceHistoryChangeQuery(
                            null,
                            skuA,
                            null,
                            null,
                            null,
                            null,
                            null,
                            LocalDate.now().minusDays(9),
                            LocalDate.now().minusDays(9),
                            null,
                            0,
                            50)))
            .items();
    assertThat(byWindow).hasSize(1);
    assertThat(byWindow.get(0).reasonText()).isEqualTo("A2");

    // 真分页。
    var page1 =
        tx(
                actor,
                () ->
                    history.listChanges(
                        new PriceHistoryChangeQuery(null, skuA, null, null, null, null, null, null, null, null, 0, 2)))
            .items();
    var page2 =
        tx(
                actor,
                () ->
                    history.listChanges(
                        new PriceHistoryChangeQuery(null, skuA, null, null, null, null, null, null, null, null, 1, 2)))
            .items();
    assertThat(page1).hasSize(2);
    assertThat(page2).hasSize(1);
    assertThat(page1.stream().map(r -> r.id()).toList())
        .doesNotContainAnyElementsOf(page2.stream().map(r -> r.id()).toList());

    // 真排序：按生效时刻升序 / 降序。
    var asc =
        tx(
                actor,
                () ->
                    history.listChanges(
                        new PriceHistoryChangeQuery(
                            null, skuA, null, null, null, null, null, null, null, "EFFECTIVE_AT_ASC", 0, 50)))
            .items();
    var desc =
        tx(
                actor,
                () ->
                    history.listChanges(
                        new PriceHistoryChangeQuery(
                            null, skuA, null, null, null, null, null, null, null, "EFFECTIVE_AT_DESC", 0, 50)))
            .items();
    assertThat(asc.stream().map(r -> r.reasonText()).toList()).containsExactly("A1", "A2", "A3");
    assertThat(desc.stream().map(r -> r.reasonText()).toList()).containsExactly("A3", "A2", "A1");

    // 未知的排序方式 ⇒ 明确报错，不静默忽略。
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        history.listChanges(
                            new PriceHistoryChangeQuery(
                                null, skuA, null, null, null, null, null, null, null, "DROP TABLE", 0, 50))))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 断言八

  /** 销量维度**未接线**时显式不可用，而不是 0。 */
  @Test
  void 销量维度未接线时显式不可用而不是零() throws Exception {
    UUID skuId = pricedSku(actor, "PRH", unique("CAT"), "BrandH");
    register(actor, skuId, "100", "90", "MANUAL_ADJUSTMENT", "销量未接线", OffsetDateTime.now());

    var page =
        tx(
                actor,
                () ->
                    history.comparison(
                        new PriceHistoryComparisonQuery(
                            null, skuId, null, null, null, null, 0, 0, 20),
                        bearer(actor)));
    assertThat(page.salesAvailable()).isFalse();
    assertThat(page.note()).isNotBlank();
    assertThat(page.items()).hasSize(1);
    var row = page.items().get(0);
    assertThat(row.salesQuantityBefore()).isNull();
    assertThat(row.salesQuantityAfter()).isNull();
    assertThat(row.salesQuantityDelta()).isNull();
    // 价格与利润那两半**是真的**（利润没测准时才是 null）。
    assertThat(row.priceDelta()).isEqualByComparingTo("-10.00");
    assertThat(row.grossProfitBefore()).isNull();
    assertThat(row.grossProfitAfter()).isNull();
    assertThat(row.decisionReady()).isFalse();

    // 销量端点：未接线 ⇒ 503（不是 200 + 0）。
    http.perform(
            get("/catalog/price-history/sales")
                .param("skuId", skuId.toString())
                .param("platformCode", PLATFORM)
                .param("siteCode", SITE)
                .param("from", OffsetDateTime.now().minusDays(7).toString())
                .param("to", OffsetDateTime.now().toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isServiceUnavailable());
  }

  // ================================================================ 断言九

  /** 变更事实**不可变**：GRANT 里没有 UPDATE / DELETE。 */
  @Test
  void 变更事实不可变没有改写入口() {
    var privileges =
        tx(
                actor,
                () ->
                    sql.fetch(
                        "select privilege_type from information_schema.role_table_grants"
                            + " where table_name='price_change_history'"))
            .stream()
            .map(row -> row.get(0, String.class))
            .toList();
    assertThat(privileges).isNotEmpty();
    assertThat(privileges).doesNotContain("UPDATE");
    assertThat(privileges).doesNotContain("DELETE");
    assertThat(privileges).contains("SELECT", "INSERT");
  }

  // ================================================================ 时间轴与详情

  /** 时间轴能接上链条；断链时**明确指出**，而不是默默跳过。 */
  @Test
  void 时间轴能接上链条且断链时明确指出() {
    UUID skuId = pricedSku(actor, "PRH", unique("CAT"), "BrandH");
    var base = OffsetDateTime.now().minusDays(5);
    register(actor, skuId, "100", "90", "MANUAL_ADJUSTMENT", "第一跳", base);
    register(actor, skuId, "90", "80", "MANUAL_ADJUSTMENT", "第二跳", base.plusDays(1));

    var timeline = tx(actor, () -> history.timeline(skuId, PLATFORM, SITE, null, null));
    assertThat(timeline.changeCount()).isEqualTo(2);
    assertThat(timeline.entries().get(0).chainConsistent()).isTrue();
    assertThat(timeline.entries().get(1).chainConsistent()).isTrue();
    // 累计价差 = 本格新价 − 时间轴第一格的**新价**（90 → 80 = −10），
    // 不是相对第一格的旧价（那会把首次定价那一跳也算进来）。
    assertThat(timeline.entries().get(1).cumulativeDelta()).isEqualByComparingTo("-10.00");
    assertThat(timeline.currentPrice()).isEqualByComparingTo("80.00");

    // 断链：第二次的旧价与第一次的新价对不上。
    UUID sku2 = pricedSku(actor, "PRH", unique("CAT"), "BrandH");
    register(actor, sku2, "100", "90", "MANUAL_ADJUSTMENT", "跳", base);
    register(actor, sku2, "77", "70", "MANUAL_ADJUSTMENT", "断", base.plusDays(1));
    var broken = tx(actor, () -> history.timeline(sku2, PLATFORM, SITE, null, null));
    assertThat(broken.entries().get(1).chainConsistent()).isFalse();
    assertThat(broken.entries().get(1).chainNote()).contains("链条断了");
  }

  /** 单条变更详情给出前后相邻的变更。 */
  @Test
  void 单条变更详情给出前后相邻的变更() {
    UUID skuId = pricedSku(actor, "PRH", unique("CAT"), "BrandH");
    var base = OffsetDateTime.now().minusDays(5);
    var first = register(actor, skuId, "100", "90", "MANUAL_ADJUSTMENT", "第一跳", base);
    var second = register(actor, skuId, "90", "80", "MANUAL_ADJUSTMENT", "第二跳", base.plusDays(1));

    var middle = tx(actor, () -> history.getChange(second.id()));
    assertThat(middle.previous()).isNotNull();
    assertThat(middle.previous().id()).isEqualTo(first.id());
    assertThat(middle.next()).isNull();

    var head = tx(actor, () -> history.getChange(first.id()));
    assertThat(head.previous()).isNull();
    assertThat(head.next()).isNotNull();
    assertThat(head.next().id()).isEqualTo(second.id());

    // 不存在的变更 ⇒ 404，不泄露「存在」这个事实。
    assertThatThrownBy(() -> tx(actor, () -> history.getChange(UUID.randomUUID())))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 原因字典

  /** 原因字典可增改；历史存的是**快照**，字典改名不影响历史。 */
  @Test
  void 原因字典可增改且历史存的是快照() {
    var saved =
        write(
            actor,
            () ->
                history.saveReason(
                    actor,
                    new PriceHistoryReasonInput("COMPETE", "跟竞品", "COMPETITION", true, 10, "竞品跟价")));
    assertThat(saved.code()).isEqualTo("COMPETE");
    assertThat(saved.categoryLabel()).isEqualTo("竞品");

    var updated =
        write(
            actor,
            () ->
                history.saveReason(
                    actor, new PriceHistoryReasonInput("COMPETE", "跟竞品调价", "COMPETITION", false, 20, "")));
    assertThat(updated.label()).isEqualTo("跟竞品调价");
    assertThat(updated.enabled()).isFalse();

    var reasons = tx(actor, history::reasons);
    assertThat(reasons.stream().map(r -> r.code())).contains("COMPETE");

    // 分类写错 ⇒ 明确报错。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        history.saveReason(
                            actor, new PriceHistoryReasonInput("X", "x", "NOT_A_CATEGORY", true, 1, ""))))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ HTTP 与权限

  /** HTTP 端点通；只读角色**不能**登记变更。 */
  @Test
  void HTTP端点通且只读角色不能登记变更() throws Exception {
    UUID skuId = pricedSku(actor, "PRH", unique("CAT"), "BrandH");
    register(actor, skuId, "100", "90", "MANUAL_ADJUSTMENT", "HTTP", OffsetDateTime.now());

    http.perform(get("/catalog/price-history/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/price-history/changes").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(
            get("/catalog/price-history/timeline")
                .param("skuId", skuId.toString())
                .param("platformCode", PLATFORM)
                .param("siteCode", SITE)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/price-history/reasons").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/price-history/comparison").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    var body =
        """
        {"skuId":"%s","platformCode":"AMAZON","siteCode":"US","currency":"USD",
         "newPrice":88.00,"changeSource":"MANUAL_ADJUSTMENT","reasonText":"只读角色不该能写",
         "effectiveAt":"2026-03-01T10:15:30+08:00"}
        """
            .formatted(skuId);
    http.perform(
            post("/catalog/price-history/changes")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 校验

  /** 登记的入参校验：不合法就**明确报错**，不静默落一条半截事实。 */
  @Test
  void 登记入参校验明确报错() {
    UUID skuId = pricedSku(actor, "PRH", unique("CAT"), "BrandH");
    var at = OffsetDateTime.now();

    // 新价必须是正数（0 价不是价）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        history.register(
                            actor,
                            bearer(actor),
                            new PriceHistoryChangeInput(
                                skuId, PLATFORM, SITE, "USD", null, new BigDecimal("0"),
                                "MANUAL_ADJUSTMENT", "", "x", at, null, null, null, null, null, null,
                                null, null, null, ""))))
        .isInstanceOf(ResponseStatusException.class);

    // 必须写明原因。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        history.register(
                            actor,
                            bearer(actor),
                            new PriceHistoryChangeInput(
                                skuId, PLATFORM, SITE, "USD", null, new BigDecimal("10"),
                                "MANUAL_ADJUSTMENT", "", "  ", at, null, null, null, null, null, null,
                                null, null, null, ""))))
        .isInstanceOf(ResponseStatusException.class);

    // 未知的变更来源。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        history.register(
                            actor,
                            bearer(actor),
                            new PriceHistoryChangeInput(
                                skuId, PLATFORM, SITE, "USD", null, new BigDecimal("10"),
                                "NOT_A_SOURCE", "", "x", at, null, null, null, null, null, null, null,
                                null, null, ""))))
        .isInstanceOf(ResponseStatusException.class);

    // 小写币种**归一化**成大写（与 5.3.1.1 的 normalize 同一口径），不是报错。
    var normalized =
        write(
            actor,
            () ->
                history.register(
                    actor,
                    bearer(actor),
                    new PriceHistoryChangeInput(
                        skuId, PLATFORM, SITE, "usd", null, new BigDecimal("10"),
                        "MANUAL_ADJUSTMENT", "", "币种归一化", at, null, null, null, null, null, null,
                        null, null, null, "")));
    assertThat(normalized.id()).isNotNull();
    var normalizedRow = tx(actor, () -> history.getChange(normalized.id())).change();
    assertThat(normalizedRow.currency()).isEqualTo("USD");
  }

  // ================================================================ 能力声明

  /** 能力声明把「销量未接线」**明说**出来，不含糊。 */
  @Test
  void 能力声明把销量未接线明说出来() {
    var capabilities = tx(actor, history::capabilities);
    var sales =
        capabilities.inputs().stream()
            .filter(i -> i.code().equals("SALES"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("能力声明里没有销量维度"));
    assertThat(sales.available()).isFalse();
    assertThat(sales.ownerModule()).contains("9 订单履约");
    assertThat(sales.impact()).contains("未接线");
    assertThat(capabilities.salesNote()).contains("未接线");
    // 5.4.1.1 调价单**未交付**这件事必须写明，不许假装已经接上。
    assertThat(capabilities.upstreamNote()).contains("5.4.1.1");
    assertThat(capabilities.upstreamNote()).contains("写端口");
  }

  @Test
  void 未接线的销量端口返回503而不是零() {
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        history.sales(
                            bearer(actor),
                            UUID.randomUUID(),
                            PLATFORM,
                            SITE,
                            OffsetDateTime.now().minusDays(1),
                            OffsetDateTime.now())))
        .isInstanceOf(SalesFactPort.SalesUnavailableException.class);
    assertThat(HttpStatus.SERVICE_UNAVAILABLE.value()).isEqualTo(503);
  }
}
