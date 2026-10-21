package com.starcon.catalog;

import static com.starcon.catalog.BatchCalculationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

import com.starcon.catalog.BatchCalculationModels.BatchCalculationRequest;
import com.starcon.catalog.BatchCalculationModels.BatchResultRow;
import com.starcon.catalog.BatchCalculationModels.BatchRunDetail;
import com.starcon.catalog.BatchCalculationModels.ResultQuery;
import com.starcon.catalog.BatchCalculationModels.ResultStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.3.1.2 批量测算：真实 PostgreSQL 16 + 强制 RLS 下的
 * 利润表落库、亏损品/低毛利品标记、真筛选与真分页、CSV 导出、跳过规则与权限分离。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：批次与结果全部真的落库。
 * 连接池固定为 1 条连接（见底座）：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的五个断言</b>：
 *
 * <ol>
 *   <li>{@link #批量测算按当前成本与规则输出利润表()}：蓝图的输出句 —— 一批 SKU 一次算完，
 *       每一行都是售价 → 每一项扣减 → 毛利额与毛利率。</li>
 *   <li>{@link #亏损品与低毛利品互斥标记()}：蓝图那句「标记亏损品与低毛利品」，
 *       而且两个标记**不能重叠** —— 重叠会让「问题品总数」多算。</li>
 *   <li>{@link #没有价格观测的SKU被跳过而不是拿0顶()}：0 会被算成「亏损品」，
 *       那是凭空造出来的清库存依据。</li>
 *   <li>{@link #结果列表支持真筛选与真分页()}：筛选与排序都在数据库层，
 *       不是把整表拉回来在内存里筛。</li>
 *   <li>{@link #导出与列表共用同一套筛选()}：导出的就是查到的那些行。</li>
 * </ol>
 */
class BatchCalculationIntegrationTest extends BatchCalcFixture {

  @Autowired BatchCalculationService calculations;
  @Autowired ProfitCalculatorService calculator;

  // ================================================================ 请求构造

  private BatchCalculationRequest request(String category, String brand) {
    return new BatchCalculationRequest(
        "AMAZON",
        "US",
        "USD",
        "USD",
        category,
        brand,
        BigDecimal.ONE,
        null,
        new BigDecimal("0.15"),
        null,
        new BigDecimal("5"),
        null,
        new BigDecimal("0.15"),
        DAY_OLD.minusDays(1),
        TODAY,
        "选品前批量测算");
  }

  private BatchRunDetail create(AccessService.Access who, BatchCalculationRequest request) {
    return write(who, () -> calculations.createRun(who, request, bearer(who)));
  }

  private static BatchResultRow rowOf(BatchResultPage page, UUID skuId) {
    for (var row : page.items()) {
      if (row.skuId().equals(skuId)) return row;
    }
    throw new AssertionError("结果表里没有这个 SKU：" + skuId);
  }

  // ================================================================ 核心断言

  /**
   * 批量测算按当前成本与规则输出利润表（蓝图的输出句）。
   *
   * <p>每一行都要能回答「售价是多少、扣了哪几项、毛利多少」——
   * 而不是一个孤零零的毛利率数字。
   */
  @Test
  void 批量测算按当前成本与规则输出利润表() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "BATCH", category, "BrandX");
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "100", null,
        TODAY.minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));

    var run = create(actor, request(category, "BrandX"));

    // 结果真的落库了。
    var stored =
        tx(
            actor,
            () ->
                sql.fetchOne(
                    "select count(*) from batch_calculation_result where run_id=?",
                    run.id()));
    assertThat(stored.get(0, Long.class)).isEqualTo(1L);

    var page =
        tx(
            actor,
            () ->
                calculations.listResults(
                    run.id(), new ResultQuery(null, null, null, null, null, null, 0, 20)));
    var row = rowOf(page, skuId);

    // 售价 100，成本构成 16（采购 10 + 头程 2 + 关税 3 + 包装 1），平台费 15，物流 5
    // ⇒ 扣减合计 36，毛利 64，毛利率 64%。
    assertThat(row.status()).isEqualTo(ResultStatus.CALCULATED);
    assertThat(row.unitPrice()).isEqualByComparingTo("100.0000");
    assertThat(row.priceSource()).isEqualTo("INTERNAL_PRICE");
    assertThat(row.currency()).isEqualTo("USD");
    assertThat(row.knownCostTotal()).isEqualByComparingTo("16.0000");
    assertThat(row.platformFeeAmount()).isEqualByComparingTo("15.0000");
    assertThat(row.logisticsAmount()).isEqualByComparingTo("5.0000");
    assertThat(row.totalDeductions()).isEqualByComparingTo("36.0000");
    assertThat(row.grossProfit()).isEqualByComparingTo("64.0000");
    assertThat(row.grossMarginRate()).isEqualByComparingTo("0.640000");
    assertThat(row.breakEvenPrice()).isEqualByComparingTo("24.7059");
    assertThat(row.decisionReady()).isTrue();
    assertThat(row.decisionBasis()).isEqualTo("COMPLETE_FROM_MANUAL_ASSUMPTIONS");
    // 缺的那两项（平台费 / 物流）是手工假设，不是「未计入」。
    assertThat(row.blockingGaps()).isEmpty();
    assertThat(row.loss()).isFalse();
    assertThat(row.lowMargin()).isFalse();

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
                        new BigDecimal("100"),
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
                        "选品前批量测算"),
                    bearer(actor)));
    assertThat(row.grossProfit()).isEqualByComparingTo(single.grossProfit());
    assertThat(row.grossMarginRate()).isEqualByComparingTo(single.grossMarginRate());
    assertThat(row.breakEvenPrice()).isEqualByComparingTo(single.breakEvenPrice());

    // 汇总数字是读时聚合出来的，且与结果表一致。
    assertThat(run.summary().total()).isEqualTo(1L);
    assertThat(run.summary().calculated()).isEqualTo(1L);
    assertThat(run.summary().skippedNoPrice()).isZero();
    assertThat(run.summary().loss()).isZero();
    assertThat(run.summary().lowMargin()).isZero();
    assertThat(run.summary().problem()).isZero();
    assertThat(run.summary().avgGrossMarginRate()).isEqualByComparingTo("0.640000");
  }

  /**
   * 亏损品与低毛利品**互斥**标记（蓝图那句「标记亏损品与低毛利品」）。
   *
   * <p>亏损：售价 10，成本 16 + 平台费 1.5 + 物流 5 ⇒ 毛利 −12.5 ⇒ 亏损品。
   * <p>低毛利：售价 25，成本 16 + 平台费 3.75 + 物流 5 ⇒ 毛利 0.25，毛利率 1% ⇒ 低毛利品（还没亏）。
   * <p>正常：售价 100 ⇒ 毛利率 64% ⇒ 无标记。
   */
  @Test
  void 亏损品与低毛利品互斥标记() {
    String category = unique("CAT");
    UUID lossSku = pricedSku(actor, "LOSS", category, "BrandL");
    UUID lowSku = pricedSku(actor, "LOW", category, "BrandL");
    UUID okSku = pricedSku(actor, "OK", category, "BrandL");
    priceObservation(actor, lossSku, "AMAZON", "US", "USD", "10", null, OffsetDateTime.now());
    priceObservation(actor, lowSku, "AMAZON", "US", "USD", "25", null, OffsetDateTime.now());
    priceObservation(actor, okSku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    var run = create(actor, request(category, "BrandL"));
    var page =
        tx(
            actor,
            () ->
                calculations.listResults(
                    run.id(), new ResultQuery(null, null, null, null, null, null, 0, 20)));

    var loss = rowOf(page, lossSku);
    assertThat(loss.grossProfit()).isEqualByComparingTo("-12.5000");
    assertThat(loss.loss()).isTrue();
    // 🔴 互斥：亏损品**不是**低毛利品（否则同一个 SKU 被计两次）。
    assertThat(loss.lowMargin()).isFalse();

    var low = rowOf(page, lowSku);
    assertThat(low.grossProfit()).isEqualByComparingTo("0.2500");
    assertThat(low.grossMarginRate()).isEqualByComparingTo("0.010000");
    assertThat(low.loss()).isFalse();
    assertThat(low.lowMargin()).isTrue();

    var ok = rowOf(page, okSku);
    assertThat(ok.loss()).isFalse();
    assertThat(ok.lowMargin()).isFalse();

    // 汇总：亏损 1 + 低毛利 1 = 问题品 2（不重复计数）。
    assertThat(run.summary().loss()).isEqualTo(1L);
    assertThat(run.summary().lowMargin()).isEqualTo(1L);
    assertThat(run.summary().problem()).isEqualTo(2L);
  }

  /**
   * 没有价格观测的 SKU **被跳过**，而不是拿 0 顶。
   *
   * <p>🔴 这是本模块最贵的一条断言：0 会被算成「亏损品」，
   * 而「亏损品」是运营拿去决定「涨价还是清仓」的依据。
   */
  @Test
  void 没有价格观测的SKU被跳过而不是拿0顶() {
    String category = unique("CAT");
    UUID noPrice = pricedSku(actor, "NOPRICE", category, "BrandN");
    UUID withPrice = pricedSku(actor, "PRICE", category, "BrandN");
    priceObservation(actor, withPrice, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    var run = create(actor, request(category, "BrandN"));
    var page =
        tx(
            actor,
            () ->
                calculations.listResults(
                    run.id(), new ResultQuery(null, null, null, null, null, null, 0, 20)));

    var skipped = rowOf(page, noPrice);
    assertThat(skipped.status()).isEqualTo(ResultStatus.SKIPPED_NO_PRICE);
    assertThat(skipped.unitPrice()).isNull();
    assertThat(skipped.grossProfit()).isNull();
    assertThat(skipped.grossMarginRate()).isNull();
    // 🔴 跳过 ≠ 亏损：标记必须是 false。
    assertThat(skipped.loss()).isFalse();
    assertThat(skipped.lowMargin()).isFalse();
    assertThat(skipped.skipReason()).contains("没有价格观测");

    assertThat(run.summary().total()).isEqualTo(2L);
    assertThat(run.summary().calculated()).isEqualTo(1L);
    assertThat(run.summary().skippedNoPrice()).isEqualTo(1L);
    assertThat(run.summary().loss()).isZero();
  }

  /** 平台在售价可以作为售价来源（我方价缺失时），且来源被标出来。 */
  @Test
  void 平台在售价可以作为售价来源() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "PLATPRICE", category, "BrandP");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", null, "80", OffsetDateTime.now());

    var run = create(actor, request(category, "BrandP"));
    var page =
        tx(
            actor,
            () ->
                calculations.listResults(
                    run.id(), new ResultQuery(null, null, null, null, null, null, 0, 20)));
    var row = rowOf(page, skuId);
    assertThat(row.unitPrice()).isEqualByComparingTo("80.0000");
    assertThat(row.priceSource()).isEqualTo("PLATFORM_PRICE");
  }

  /** 取**最新一条**价格观测（台账是只追加的）。 */
  @Test
  void 取最新一条价格观测() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "LATEST", category, "BrandL");
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "50", null,
        TODAY.minusDays(10).atStartOfDay().atOffset(ZoneOffset.UTC));
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "120", null,
        TODAY.minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));

    var run = create(actor, request(category, "BrandL"));
    var page =
        tx(
            actor,
            () ->
                calculations.listResults(
                    run.id(), new ResultQuery(null, null, null, null, null, null, 0, 20)));
    var row = rowOf(page, skuId);
    assertThat(row.unitPrice()).isEqualByComparingTo("120.0000");
    // 🔴 时间字段写回不偏 28800s：观测时刻必须与写进去的那一刻一致。
    assertThat(row.priceObservedAt()).isNotNull();
    assertThat(row.priceObservedAt().toInstant())
        .isEqualTo(TODAY.minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC).toInstant());
  }

  // ================================================================ 范围

  /** 范围按分类 / 品牌精确匹配（模糊匹配会静默多算一批 SKU）。 */
  @Test
  void 范围按分类与品牌精确匹配() {
    String category = unique("CAT");
    UUID wanted = pricedSku(actor, "SCOPE", category, "BrandWanted");
    UUID otherBrand = pricedSku(actor, "SCOPE", category, "BrandOther");
    priceObservation(actor, wanted, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    priceObservation(actor, otherBrand, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    var run = create(actor, request(category, "BrandWanted"));
    var page =
        tx(
            actor,
            () ->
                calculations.listResults(
                    run.id(), new ResultQuery(null, null, null, null, null, null, 0, 20)));
    assertThat(page.total()).isEqualTo(1L);
    assertThat(page.items().get(0).skuId()).isEqualTo(wanted);
    assertThat(run.summary().total()).isEqualTo(1L);
  }

  // ================================================================ 筛选与分页

  /** 结果列表支持**真筛选**（毛利率 &lt; X、售价 &lt; Y、异常标记）与**真分页**。 */
  @Test
  void 结果列表支持真筛选与真分页() {
    String category = unique("CAT");
    UUID loss = pricedSku(actor, "F", category, "BrandF");
    UUID low = pricedSku(actor, "F", category, "BrandF");
    UUID ok = pricedSku(actor, "F", category, "BrandF");
    priceObservation(actor, loss, "AMAZON", "US", "USD", "10", null, OffsetDateTime.now());
    priceObservation(actor, low, "AMAZON", "US", "USD", "25", null, OffsetDateTime.now());
    priceObservation(actor, ok, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    var run = create(actor, request(category, "BrandF"));

    // 毛利率 < 5% ⇒ 命中亏损（−125%）与低毛利（1%），不命中正常的 64%。
    var lowMargin =
        tx(
            actor,
            () ->
                calculations.listResults(
                    run.id(),
                    new ResultQuery(new BigDecimal("0.05"), null, null, null, null, null, 0, 20)));
    assertThat(lowMargin.total()).isEqualTo(2L);
    assertThat(lowMargin.items()).extracting(BatchResultRow::skuId).containsExactlyInAnyOrder(loss, low);

    // 售价 < 30 ⇒ 只命中那两个便宜的。
    var cheap =
        tx(
            actor,
            () ->
                calculations.listResults(
                    run.id(),
                    new ResultQuery(null, new BigDecimal("30"), null, null, null, null, 0, 20)));
    assertThat(cheap.total()).isEqualTo(2L);

    // 异常标记筛选。
    var lossOnly =
        tx(
            actor,
            () ->
                calculations.listResults(
                    run.id(),
                    new ResultQuery(null, null, "LOSS", null, null, null, 0, 20)));
    assertThat(lossOnly.total()).isEqualTo(1L);
    assertThat(lossOnly.items().get(0).skuId()).isEqualTo(loss);

    var lowOnly =
        tx(
            actor,
            () ->
                calculations.listResults(
                    run.id(),
                    new ResultQuery(null, null, "LOW_MARGIN", null, null, null, 0, 20)));
    assertThat(lowOnly.total()).isEqualTo(1L);
    assertThat(lowOnly.items().get(0).skuId()).isEqualTo(low);

    // 关键字筛选。
    var byKeyword =
        tx(
            actor,
            () ->
                calculations.listResults(
                    run.id(), new ResultQuery(null, null, null, null, "F-", null, 0, 20)));
    assertThat(byKeyword.total()).isEqualTo(3L);

    // 真分页：total 是真实命中数，items 是这一页。
    var firstPage =
        tx(
            actor,
            () ->
                calculations.listResults(
                    run.id(), new ResultQuery(null, null, null, null, null, null, 0, 1)));
    assertThat(firstPage.total()).isEqualTo(3L);
    assertThat(firstPage.items()).hasSize(1);
    var secondPage =
        tx(
            actor,
            () ->
                calculations.listResults(
                    run.id(), new ResultQuery(null, null, null, null, null, null, 1, 1)));
    assertThat(secondPage.total()).isEqualTo(3L);
    assertThat(secondPage.items()).hasSize(1);
    assertThat(secondPage.items().get(0).skuId()).isNotEqualTo(firstPage.items().get(0).skuId());

    // 未知筛选值必须 400，不能被静默忽略。
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        calculations.listResults(
                            run.id(), new ResultQuery(null, null, "NOPE", null, null, null, 0, 20))))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 导出

  /** 导出与列表共用同一套筛选 —— 导出的就是查到的那些行。 */
  @Test
  void 导出与列表共用同一套筛选() {
    String category = unique("CAT");
    UUID loss = pricedSku(actor, "X", category, "BrandX");
    UUID ok = pricedSku(actor, "X", category, "BrandX");
    priceObservation(actor, loss, "AMAZON", "US", "USD", "10", null, OffsetDateTime.now());
    priceObservation(actor, ok, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    var run = create(actor, request(category, "BrandX"));

    var all =
        tx(
            actor,
            () ->
                calculations.exportCsv(
                    run.id(), new ResultQuery(null, null, null, null, null, null, 0, 20)));
    assertThat(all.length).isPositive();

    var filtered =
        tx(
            actor,
            () ->
                calculations.exportCsv(
                    run.id(),
                    new ResultQuery(null, null, "LOSS", null, null, null, 0, 20)));
    var text = new String(filtered, java.nio.charset.StandardCharsets.UTF_8);
    // 表头在。
    assertThat(text).contains("SKU编码").contains("毛利率").contains("异常标记");
    // 只导出命中的那一行。
    assertThat(text.lines().filter(line -> line.contains(",10.0000,")).count()).isEqualTo(1L);
    assertThat(text).contains("亏损品");
    // 正常那行不在导出里（它的售价是 100.0000）。
    assertThat(text.lines().filter(line -> line.contains(",100.0000,")).count()).isZero();
  }

  /** CSV 单元格里的公式注入被切断（SKU 名称是外部导入进来的自由文本）。 */
  @Test
  void CSV单元格里的公式注入被切断() {
    var injected = BatchCalculationRules.csvCell("=HYPERLINK(\"http://evil\",\"点我\")");
    assertThat(injected).startsWith("\"").endsWith("\"");
    assertThat(injected).contains("'=HYPERLINK");
    assertThat(BatchCalculationRules.csvCell("a,b")).isEqualTo("\"a,b\"");
    // 含引号的值按 RFC 4180 包裹，内部引号翻倍。
    var quoted = BatchCalculationRules.csvCell("说\"话\"");
    assertThat(quoted).startsWith("\"").endsWith("\"");
    assertThat(quoted).contains("说\"\"话\"\"");
    assertThat(BatchCalculationRules.csvCell(null)).isEmpty();
    assertThat(BatchCalculationRules.csvCell("普通名字")).isEqualTo("普通名字");
  }

  // ================================================================ HTTP 端点

  /** HTTP 端点真的跑得通（不是只在服务层能调）。 */
  @Test
  void HTTP端点跑得通() throws Exception {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "HTTP", category, "BrandH");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    // 批次总数是**相对**断言：同一租户里别的测试也会建批次，不能写死 1。
    var before = tx(actor, () -> calculations.listRuns(0, 1).total());

    http.perform(
            post("/catalog/batch-calculations/runs")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(JSON.writeValueAsString(request(category, "BrandH"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.total").value(1));

    http.perform(get("/catalog/batch-calculations/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priceSourceNote").isNotEmpty());

    http.perform(get("/catalog/batch-calculations/sites").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    http.perform(get("/catalog/batch-calculations/runs").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(before.intValue() + 1));

    http.perform(get("/catalog/batch-calculations/runs").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    // 导出的字节是真的 CSV。
    var runId =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select id from batch_calculation_run order by created_at desc limit 1")
                        .get(0, UUID.class))
            .toString();
    var response =
        http.perform(
                get("/catalog/batch-calculations/runs/" + runId + "/export")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", containsString("batch-calculation-")))
            .andReturn()
            .getResponse();
    assertThat(response.getContentType()).contains("text/csv");
    assertThat(response.getContentAsString()).contains("SKU编码");
  }

  // ================================================================ 上游不可达

  /** 上游不可达 ⇒ 整批失败（503），**不**落一个半截批次。 */
  @Test
  void 上游不可达时整批失败而不是落半截批次() throws Exception {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "DOWN", category, "BrandD");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    warehouseDown.set(true);
    try {
      assertThatThrownBy(() -> create(actor, request(category, "BrandD")))
          .isInstanceOf(ResponseStatusException.class);
      // 半截批次不许留在库里。
      var count =
          tx(
              actor,
              () ->
                  sql.fetchOne(
                          "select count(*) from batch_calculation_run where scope_category=?",
                          category)
                      .get(0, Long.class));
      assertThat(count.longValue()).isZero();
    } finally {
      warehouseDown.set(false);
    }
  }

  // ================================================================ 权限

  /** 只读角色不能发起批量测算（写动作与读动作的权限码必须分开）。 */
  @Test
  void 只读角色不能发起批量测算() throws Exception {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "READONLY", category, "BrandR");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    http.perform(
            post("/catalog/batch-calculations/runs")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(JSON.writeValueAsString(request(category, "BrandR"))))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 时间

  /** 批次创建时刻写回不偏 28800s（timestamptz 用 OffsetDateTime 绑定）。 */
  @Test
  void 批次创建时刻写回不偏28800秒() {
    String category = unique("CAT");
    UUID skuId = pricedSku(actor, "TIME", category, "BrandT");
    priceObservation(actor, skuId, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());

    var run = create(actor, request(category, "BrandT"));
    var stored =
        tx(
            actor,
            () ->
                sql.fetchOne(
                    "select created_at from batch_calculation_run where id=?", run.id()));
    assertThat(stored.get("created_at", OffsetDateTime.class).toInstant())
        .isEqualTo(run.createdAt().toInstant());
  }
}
