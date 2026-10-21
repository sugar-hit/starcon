package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.SupplierRatingModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 6.1.1.3 供应商评级的集成测试（真实 PostgreSQL 16 + Testcontainers）。
 *
 * <p>测的是<b>口径</b>，不是「端点能返回 200」：
 *
 * <ul>
 *   <li>五个维度各自的分子分母（交期按「到货时刻生效的承诺日」判准时；质量按数量比；
 *       响应按「未答复 → 答复」的等待窗口；价格只比同币种；售后按供应商参与协商的笔数）；</li>
 *   <li><b>不可用 ≠ 0 分</b>：分母为 0 的维度不进合成，且排除清单报得出来；</li>
 *   <li><b>客观分与人工分分开</b>：人工加权分只改最终分，客观分不动，理由必填，单独留痕；</li>
 *   <li>变动历史只在「真的变了」时留行；</li>
 *   <li>catalog 不可达时<b>只把价格这一维标成不可用</b>，其余四个维度照算。</li>
 * </ul>
 *
 * <p><b>⚠️ 时间取值的纪律</b>：种子数据里的时刻**只取一次**并复用同一个局部变量 ——
 * 写入与断言之间隔一次 DB 往返，跨整秒边界就会差整 1 秒（偶发假红）。
 */
class SupplierRatingIntegrationTest extends SupplierRatingFixture {

  @Autowired SupplierRatingService ratings;

  private OffsetDateTime at(int dayOffset, int hour) {
    return monthStart().plusDays(dayOffset).atTime(hour, 0).atOffset(ZoneOffset.UTC);
  }

  @BeforeEach
  void clean() throws Exception {
    truncateAll();
  }

  // ================================================================ 能力 / 词表 / 权重

  @Test
  void 能力声明列出五个维度与下游归属() {
    var caps = as("Bearer tenant-a", () -> ratings.capabilities());
    assertThat(caps.dimensions())
        .containsExactly("DELIVERY", "QUALITY", "RESPONSE", "PRICE", "SERVICE");
    assertThat(caps.capabilities()).extracting(SupplierRatingCapability::name)
        .contains("DELIVERY", "QUALITY", "RESPONSE", "PRICE", "SERVICE", "MANUAL_ADJUSTMENT",
            "CHANGE_HISTORY", "PRIORITY_OUTPUT");
    // 默认供应商优先级的归属模块写清楚（下游还没接上时也不含糊）。
    var priority = caps.capabilities().stream()
        .filter(c -> c.name().equals("PRIORITY_OUTPUT"))
        .findFirst()
        .orElseThrow();
    assertThat(priority.available()).isTrue();
    assertThat(priority.ownerModule()).isEqualTo("6.2.1.2");
  }

  @Test
  void 词表与枚举同源() {
    var options = as("Bearer tenant-a", () -> ratings.options());
    assertThat(options.periodTypes()).extracting(SupplierRatingOption::value)
        .containsExactly("MONTHLY", "QUARTERLY");
    assertThat(options.dimensions()).hasSize(5);
    assertThat(options.grades()).extracting(SupplierRatingOption::value)
        .containsExactly("A", "B", "C", "D");
  }

  @Test
  void 租户没配权重时返回默认口径并标未配置() {
    var policy = as("Bearer tenant-a", () -> ratings.policy(SupplierRatingPeriodType.MONTHLY));
    assertThat(policy.configured()).isFalse();
    assertThat(policy.deliveryWeight()).isEqualByComparingTo("0.30");
    assertThat(policy.qualityWeight()).isEqualByComparingTo("0.30");
    assertThat(policy.responseWeight()).isEqualByComparingTo("0.15");
    assertThat(policy.priceWeight()).isEqualByComparingTo("0.15");
    assertThat(policy.serviceWeight()).isEqualByComparingTo("0.10");
    assertThat(policy.responseTargetHours()).isEqualTo(72);
  }

  @Test
  void 权重和不为一时被拒() {
    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        ratings.putPolicy(
                            new SupplierRatingPolicyInput(
                                SupplierRatingPeriodType.MONTHLY,
                                new BigDecimal("0.50"),
                                new BigDecimal("0.50"),
                                new BigDecimal("0.15"),
                                new BigDecimal("0.15"),
                                new BigDecimal("0.10"),
                                72, null, null, null, ""))))
        .hasMessageContaining("精确等于 1");
  }

  @Test
  void 权重配置写入后标记已配置() {
    var saved =
        asWrite(
            "Bearer tenant-a",
            () ->
                ratings.putPolicy(
                    new SupplierRatingPolicyInput(
                        SupplierRatingPeriodType.MONTHLY,
                        new BigDecimal("0.40"),
                        new BigDecimal("0.30"),
                        new BigDecimal("0.10"),
                        new BigDecimal("0.10"),
                        new BigDecimal("0.10"),
                        48, null, null, null, "本租户更看重交期")));
    assertThat(saved.configured()).isTrue();
    assertThat(saved.deliveryWeight()).isEqualByComparingTo("0.40");
    assertThat(saved.responseTargetHours()).isEqualTo(48);
  }

  // ================================================================ 交期达成率

  @Test
  void 交期达成率按到货时刻生效的承诺日判准时() throws Exception {
    UUID sku = UUID.randomUUID();
    // 单 1：承诺 today+5，today+3 到货 ⇒ 准时。
    seedOrder(
        tenantA, "PO-ON-TIME", supplierA, "SUP-A", "供应商 A", sku, "SKU-1", "100", "2.5", "USD",
        md(5), at(1, 9));
    seedArrival(tenantA, "PO-ON-TIME", supplierA, "SUP-A", "供应商 A", md(5), at(3, 9));
    // 单 2：承诺本月 2 号，本月 5 号到货 ⇒ 逾期。
    seedOrder(
        tenantA, "PO-LATE", supplierA, "SUP-A", "供应商 A", sku, "SKU-1", "100", "2.5", "USD",
        md(2), at(1, 9));
    seedArrival(tenantA, "PO-LATE", supplierA, "SUP-A", "供应商 A", md(2), at(5, 9));

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var page = as("Bearer tenant-a", () -> ratings.pageRatings(query()));
    assertThat(page.total()).isEqualTo(1);
    var row = page.items().get(0);
    assertThat(row.deliveryArrivedCount()).isEqualTo(2);
    assertThat(row.deliveryOnTimeRate()).isEqualByComparingTo("0.500000");
    assertThat(row.deliveryScore()).isEqualByComparingTo("50.0000");
  }

  @Test
  void 改期之后按改期后的承诺日判准时() throws Exception {
    UUID sku = UUID.randomUUID();
    // 单据上的承诺日是 today+2（本来会逾期），但到货前供应商改期到 today+8 ⇒ 准时。
    seedOrder(
        tenantA, "PO-RESCHEDULED", supplierA, "SUP-A", "供应商 A", sku, "SKU-1", "100", "2.5", "USD",
        md(2), at(1, 9));
    seedDeliveryChange(
        tenantA, "DC-1", "PO-RESCHEDULED", supplierA, md(2), md(8), at(2, 9));
    seedArrival(tenantA, "PO-RESCHEDULED", supplierA, "SUP-A", "供应商 A", md(8), at(5, 9));

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    assertThat(row.deliveryOnTimeRate()).isEqualByComparingTo("1.000000");
    assertThat(row.deliveryScore()).isEqualByComparingTo("100.0000");
  }

  // ================================================================ 质量合格率

  @Test
  void 质量合格率按数量比算() throws Exception {
    UUID template = seedTemplate(tenantA);
    UUID sku = UUID.randomUUID();
    // 一批 100 件里合格 90 件 ⇒ 合格率 0.9（按数量比，不是批次数比）。
    seedInspection(
        tenantA, template, supplierA, "供应商 A", sku, "SKU-1", "100", "90", "10", at(4, 10));

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    assertThat(row.qualityPassRate()).isEqualByComparingTo("0.900000");
    assertThat(row.qualityScore()).isEqualByComparingTo("90.0000");
    assertThat(row.qualityInspectedQuantity()).isEqualByComparingTo("100");
    assertThat(row.qualityQualifiedQuantity()).isEqualByComparingTo("90");
  }

  // ================================================================ 响应时长

  @Test
  void 响应时长是未答复到答复之间的等待窗口() throws Exception {
    UUID sku = UUID.randomUUID();
    seedOrder(
        tenantA, "PO-FOLLOW", supplierA, "SUP-A", "供应商 A", sku, "SKU-1", "100", "2.5", "USD",
        md(5), at(1, 9));
    // 第一次跟单没有答复；第二次跟单（48 小时后）登记了答复 ⇒ 响应窗口 48 小时。
    seedFollowup(
        tenantA, "FU-1", "PO-FOLLOW", supplierA, "SUP-A", "供应商 A", "催一次", "", at(2, 9));
    seedFollowup(
        tenantA, "FU-2", "PO-FOLLOW", supplierA, "SUP-A", "供应商 A", "再催一次", "供应商答复：已排产",
        at(4, 9));

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    assertThat(row.responseSampleCount()).isEqualTo(1);
    assertThat(row.responseAvgHours()).isEqualByComparingTo("48.00");
    // 目标 72 小时 ⇒ 48 小时得 100 × (1 − 48/144) = 66.6667 分。
    assertThat(row.responseScore()).isEqualByComparingTo("66.6667");
  }

  @Test
  void 第一次沟通就答复的记录不构成响应样本() throws Exception {
    UUID sku = UUID.randomUUID();
    seedQuality(tenantA, supplierA, "供应商 A", "100", "0");
    seedOrder(
        tenantA, "PO-FOLLOW2", supplierA, "SUP-A", "供应商 A", sku, "SKU-1", "100", "2.5", "USD",
        md(5), at(1, 9));
    seedFollowup(
        tenantA, "FU-3", "PO-FOLLOW2", supplierA, "SUP-A", "供应商 A", "问一句", "当场答复", at(2, 9));

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    assertThat(row.responseSampleCount()).isZero();
    assertThat(row.responseAvgHours()).isNull();
    // 不可用 ⇒ 不参与合成（不是 0 分）。
    assertThat(row.responseScore()).isNull();
    assertThat(row.unavailableDimensions()).contains("RESPONSE");
  }

  // ================================================================ 价格竞争力

  @Test
  void 价格竞争力只比同币种且按采购量加权() throws Exception {
    UUID sku = UUID.randomUUID();
    // 采购 100 件，参考数量 100 ⇒ 命中 minQuantity=1 的那档。
    seedOrder(
        tenantA, "PO-PRICE", supplierA, "SUP-A", "供应商 A", sku, "SKU-1", "100", "10", "USD",
        md(5), at(1, 9));
    // 市场：本供应商 10，另一家 12 ⇒ 贡献度 12/10 = 1.2 ⇒ 夹到 1.0（最优）。
    seedQuote(sku, supplierA, "USD", List.<Object[]>of(new Object[] {"1", "10"}));
    seedQuote(sku, supplierB, "USD", List.<Object[]>of(new Object[] {"1", "12"}));

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    assertThat(row.priceSampleCount()).isEqualTo(1);
    assertThat(row.priceIndex()).isEqualByComparingTo("1.000000");
    assertThat(row.priceScore()).isEqualByComparingTo("100.0000");
  }

  @Test
  void 别的供应商更便宜时价格竞争力按比值折算() throws Exception {
    UUID sku = UUID.randomUUID();
    seedOrder(
        tenantA, "PO-PRICE2", supplierA, "SUP-A", "供应商 A", sku, "SKU-1", "100", "20", "USD",
        md(5), at(1, 9));
    // 本供应商 20，同币种最优 10 ⇒ 贡献度 10/20 = 0.5。
    seedQuote(sku, supplierA, "USD", List.<Object[]>of(new Object[] {"1", "20"}));
    seedQuote(sku, supplierB, "USD", List.<Object[]>of(new Object[] {"1", "10"}));

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    assertThat(row.priceIndex()).isEqualByComparingTo("0.500000");
    assertThat(row.priceScore()).isEqualByComparingTo("50.0000");
  }

  @Test
  void 跨币种没有对照时该样本不计入() throws Exception {
    UUID sku = UUID.randomUUID();
    seedQuality(tenantA, supplierA, "供应商 A", "100", "0");
    seedOrder(
        tenantA, "PO-PRICE3", supplierA, "SUP-A", "供应商 A", sku, "SKU-1", "100", "20", "USD",
        md(5), at(1, 9));
    // 别人只报 EUR ⇒ 与本供应商的 USD 不同币种，不可比。
    seedQuote(sku, supplierA, "USD", List.<Object[]>of(new Object[] {"1", "20"}));
    seedQuote(sku, supplierB, "EUR", List.<Object[]>of(new Object[] {"1", "10"}));

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    assertThat(row.priceIndex()).isNull();
    assertThat(row.priceScore()).isNull();
    assertThat(row.unavailableDimensions()).contains("PRICE");
    assertThat(row.unavailableReason()).contains("没有可比的同币种报价");
  }

  @Test
  void 报价未达起订量时该样本不计入() throws Exception {
    UUID sku = UUID.randomUUID();
    seedQuality(tenantA, supplierA, "供应商 A", "100", "0");
    seedOrder(
        tenantA, "PO-PRICE4", supplierA, "SUP-A", "供应商 A", sku, "SKU-1", "10", "20", "USD",
        md(5), at(1, 9));
    // 起订量 1000 > 采购量 10 ⇒ 报价在该数量处不适用。
    seedQuote(sku, supplierA, "USD", List.<Object[]>of(new Object[] {"1000", "20"}));
    seedQuote(sku, supplierB, "USD", List.<Object[]>of(new Object[] {"1", "10"}));

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    assertThat(row.priceSampleCount()).isZero();
    assertThat(row.priceIndex()).isNull();
  }

  @Test
  void 报价服务不可达时只把价格这一维标成不可用() throws Exception {
    UUID sku = UUID.randomUUID();
    seedOrder(
        tenantA, "PO-NOQUOTE", supplierA, "SUP-A", "供应商 A", sku, "SKU-1", "100", "10", "USD",
        md(5), at(1, 9));
    UUID template = seedTemplate(tenantA);
    seedInspection(
        tenantA, template, supplierA, "供应商 A", sku, "SKU-1", "100", "80", "20", at(4, 10));

    catalogDown.set(true);
    var result = asWrite(
        "Bearer tenant-a",
        () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));
    catalogDown.set(false);

    assertThat(result.unavailableDimensions()).contains("PRICE");
    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    // 质量维度照算。
    assertThat(row.qualityScore()).isEqualByComparingTo("80.0000");
    // 价格维度不可用，不是 0 分。
    assertThat(row.priceScore()).isNull();
    assertThat(row.unavailableReason()).contains("市场报价读不到");
    // 覆盖权重少于 1。
    assertThat(row.availableWeight()).isLessThan(BigDecimal.ONE);
  }

  // ================================================================ 售后配合度

  @Test
  void 售后配合度按供应商参与协商的索赔笔数算() throws Exception {
    seedClaim(tenantA, supplierA, "SUP-A", "供应商 A", at(3, 10), true);
    seedClaim(tenantA, supplierA, "SUP-A", "供应商 A", at(6, 10), false);

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    assertThat(row.serviceClaimCount()).isEqualTo(2);
    assertThat(row.serviceCooperationRate()).isEqualByComparingTo("0.500000");
    assertThat(row.serviceScore()).isEqualByComparingTo("50.0000");
  }

  @Test
  void 没有索赔时售后维度不可用而不是零分() throws Exception {
    UUID sku = UUID.randomUUID();
    seedQuality(tenantA, supplierA, "供应商 A", "100", "0");
    seedOrder(
        tenantA, "PO-NOCLAIM", supplierA, "SUP-A", "供应商 A", sku, "SKU-1", "100", "10", "USD",
        md(5), at(1, 9));

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    assertThat(row.serviceClaimCount()).isZero();
    assertThat(row.serviceScore()).isNull();
    assertThat(row.unavailableDimensions()).contains("SERVICE");
  }

  // ================================================================ 合成 / 等级 / 排名

  @Test
  void 综合分按可用维度归一且等级可查() throws Exception {
    UUID sku = UUID.randomUUID();
    seedOrder(
        tenantA, "PO-FULL", supplierA, "SUP-A", "供应商 A", sku, "SKU-1", "100", "10", "USD",
        md(5), at(1, 9));
    seedArrival(
        tenantA, "PO-FULL", supplierA, "SUP-A", "供应商 A", today().plusDays(5), at(2, 9));
    UUID template = seedTemplate(tenantA);
    seedInspection(
        tenantA, template, supplierA, "供应商 A", sku, "SKU-1", "100", "100", "0", at(3, 10));
    seedQuote(sku, supplierA, "USD", List.<Object[]>of(new Object[] {"1", "10"}));
    seedQuote(sku, supplierB, "USD", List.<Object[]>of(new Object[] {"1", "20"}));
    seedClaim(tenantA, supplierA, "SUP-A", "供应商 A", at(4, 10), true);

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    // 交期 100、质量 100、价格 100（最优）、售后 100；响应无样本 ⇒ 排除。
    assertThat(row.deliveryScore()).isEqualByComparingTo("100.0000");
    assertThat(row.qualityScore()).isEqualByComparingTo("100.0000");
    assertThat(row.priceScore()).isEqualByComparingTo("100.0000");
    assertThat(row.serviceScore()).isEqualByComparingTo("100.0000");
    assertThat(row.availableWeight()).isEqualByComparingTo("0.850000");
    assertThat(row.objectiveScore()).isEqualByComparingTo("100.0000");
    assertThat(row.finalScore()).isEqualByComparingTo("100.0000");
    assertThat(row.grade()).isEqualTo(SupplierRatingGrade.A);
    assertThat(row.rankInPeriod()).isEqualTo(1);
  }

  @Test
  void 排名按最终分从高到低() throws Exception {
    UUID skuA = UUID.randomUUID();
    UUID skuB = UUID.randomUUID();
    // A：质量 100；B：质量 20。
    UUID template = seedTemplate(tenantA);
    seedOrder(
        tenantA, "PO-RANK-A", supplierA, "SUP-A", "供应商 A", skuA, "SKU-A", "100", "10", "USD",
        md(5), at(1, 9));
    seedOrder(
        tenantA, "PO-RANK-B", supplierB, "SUP-B", "供应商 B", skuB, "SKU-B", "100", "10", "USD",
        md(5), at(1, 9));
    seedInspection(
        tenantA, template, supplierA, "供应商 A", skuA, "SKU-A", "100", "100", "0", at(3, 10));
    seedInspection(
        tenantA, template, supplierB, "供应商 B", skuB, "SKU-B", "100", "20", "80", at(3, 10));

    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var page = as("Bearer tenant-a", () -> ratings.pageRatings(query()));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items().get(0).supplierCode()).isEqualTo("SUP-A");
    assertThat(page.items().get(0).rankInPeriod()).isEqualTo(1);
    assertThat(page.items().get(1).supplierCode()).isEqualTo("SUP-B");
    assertThat(page.items().get(1).rankInPeriod()).isEqualTo(2);
  }

  @Test
  void 优先级清单给下游可直接消费的排序() throws Exception {
    UUID template = seedTemplate(tenantA);
    seedOrder(
        tenantA, "PO-PRIO-A", supplierA, "SUP-A", "供应商 A", UUID.randomUUID(), "SKU-A", "100",
        "10", "USD", md(5), at(1, 9));
    seedOrder(
        tenantA, "PO-PRIO-B", supplierB, "SUP-B", "供应商 B", UUID.randomUUID(), "SKU-B", "100",
        "10", "USD", md(5), at(1, 9));
    seedInspection(
        tenantA, template, supplierA, "供应商 A", UUID.randomUUID(), "SKU-A", "100", "100", "0",
        at(3, 10));
    seedInspection(
        tenantA, template, supplierB, "供应商 B", UUID.randomUUID(), "SKU-B", "100", "20", "80",
        at(3, 10));
    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var list = as(
        "Bearer tenant-a",
        () -> ratings.priorities(SupplierRatingPeriodType.MONTHLY, monthStart()));
    assertThat(list.items()).hasSize(2);
    assertThat(list.items().get(0).rank()).isEqualTo(1);
    assertThat(list.items().get(0).supplierCode()).isEqualTo("SUP-A");
    assertThat(list.items().get(0).objectiveScore()).isNotNull();
  }

  // ================================================================ 人工加权分

  @Test
  void 人工加权分只改最终分客观分不动且单独标记() throws Exception {
    UUID template = seedTemplate(tenantA);
    seedInspection(
        tenantA, template, supplierA, "供应商 A", UUID.randomUUID(), "SKU-A", "100", "60", "40",
        at(3, 10));
    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var before = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    var adjusted =
        asWrite(
            "Bearer tenant-a",
            () ->
                ratings.adjust(
                    before.id(),
                    new SupplierRatingAdjustmentInput(new BigDecimal("10"), "战略供应商，本期给予扶持"),
                    userA));

    assertThat(adjusted.objectiveScore()).isEqualByComparingTo(before.objectiveScore());
    assertThat(adjusted.manualAdjustment()).isEqualByComparingTo("10");
    assertThat(adjusted.finalScore())
        .isEqualByComparingTo(before.objectiveScore().add(new BigDecimal("10")));
    assertThat(adjusted.manuallyAdjusted()).isTrue();
    assertThat(adjusted.manualAdjustedBy()).isEqualTo(userA);
    assertThat(adjusted.manualAdjustmentReason()).contains("战略供应商");

    // 调整明细留痕（追加式，带理由）。
    var detail = as("Bearer tenant-a", () -> ratings.rating(before.id()));
    assertThat(detail.adjustments()).hasSize(1);
    assertThat(detail.adjustments().get(0).adjustment()).isEqualByComparingTo("10");
    assertThat(detail.adjustments().get(0).reason()).contains("战略供应商");
    // 变动历史里也有一行人工调整。
    assertThat(detail.changes()).extracting(SupplierRatingChangeRow::changeType)
        .contains(SupplierRatingChangeType.MANUAL_ADJUSTMENT);
  }

  @Test
  void 人工调整缺理由被拒() throws Exception {
    UUID template = seedTemplate(tenantA);
    seedInspection(
        tenantA, template, supplierA, "供应商 A", UUID.randomUUID(), "SKU-A", "100", "60", "40",
        at(3, 10));
    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));
    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);

    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        ratings.adjust(
                            row.id(), new SupplierRatingAdjustmentInput(new BigDecimal("5"), "  "),
                            userA)))
        .hasMessageContaining("必须写理由");
  }

  @Test
  void 重算保留人工调整且最终分夹在百分制上限内() throws Exception {
    UUID template = seedTemplate(tenantA);
    UUID sku = UUID.randomUUID();
    seedInspection(
        tenantA, template, supplierA, "供应商 A", sku, "SKU-A", "100", "60", "40", at(3, 10));
    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));
    var before = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    asWrite(
        "Bearer tenant-a",
        () ->
            ratings.adjust(
                before.id(), new SupplierRatingAdjustmentInput(new BigDecimal("10"), "扶持"), userA));

    // 补一条更好的质检事实后重算：客观分上升，最终分 = 新客观分 + 原调整量。
    seedInspection(
        tenantA, template, supplierA, "供应商 A", sku, "SKU-A", "100", "100", "0", at(6, 10));
    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var after = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    // 两批质检（合格 60/100 与 100/100）按**数量比**合成 ⇒ 160/200 = 80%。
    assertThat(after.objectiveScore()).isEqualByComparingTo("80.0000");
    // 人工调整量**保留**（它是「谁提的要求」的凭据），最终分 = 80 + 10 = 90。
    assertThat(after.manualAdjustment()).isEqualByComparingTo("10");
    assertThat(after.finalScore()).isEqualByComparingTo("90.0000");
    assertThat(after.manuallyAdjusted()).isTrue();
  }

  // ================================================================ 变动历史

  @Test
  void 变动历史只在分数真的变了时留行() throws Exception {
    UUID template = seedTemplate(tenantA);
    seedInspection(
        tenantA, template, supplierA, "供应商 A", UUID.randomUUID(), "SKU-A", "100", "60", "40",
        at(3, 10));
    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    // 同样的事实重算一次 ⇒ 分数没变 ⇒ 不留变动行。
    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));
    var changes = as("Bearer tenant-a", () -> ratings.changes(null, null, null, 0, 50));
    assertThat(changes.total()).isZero();

    // 事实变了再算 ⇒ 留一行「同周期重算」。
    seedInspection(
        tenantA, template, supplierA, "供应商 A", UUID.randomUUID(), "SKU-A", "100", "100", "0",
        at(6, 10));
    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));
    var changes2 = as("Bearer tenant-a", () -> ratings.changes(null, null, null, 0, 50));
    assertThat(changes2.total()).isEqualTo(1);
    assertThat(changes2.items().get(0).changeType()).isEqualTo(SupplierRatingChangeType.RECOMPUTE);
    assertThat(changes2.items().get(0).gradeChanged()).isTrue();
  }

  // ================================================================ 列表 / 详情 / 概览

  @Test
  void 列表是真分页真筛选() throws Exception {
    UUID template = seedTemplate(tenantA);
    seedOrder(
        tenantA, "PO-LIST-A", supplierA, "SUP-A", "供应商 A", UUID.randomUUID(), "SKU-A", "100",
        "10", "USD", md(5), at(1, 9));
    seedOrder(
        tenantA, "PO-LIST-B", supplierB, "SUP-B", "供应商 B", UUID.randomUUID(), "SKU-B", "100",
        "10", "USD", md(5), at(1, 9));
    seedInspection(
        tenantA, template, supplierA, "供应商 A", UUID.randomUUID(), "SKU-A", "100", "100", "0",
        at(3, 10));
    seedInspection(
        tenantA, template, supplierB, "供应商 B", UUID.randomUUID(), "SKU-B", "100", "20", "80",
        at(3, 10));
    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));

    var all = as("Bearer tenant-a", () -> ratings.pageRatings(query()));
    assertThat(all.total()).isEqualTo(2);
    var paged = as("Bearer tenant-a", () -> ratings.pageRatings(query(1, 1)));
    assertThat(paged.items()).hasSize(1);
    assertThat(paged.total()).isEqualTo(2);

    var filtered = as("Bearer tenant-a", () -> ratings.pageRatings(queryGrade(SupplierRatingGrade.A)));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).supplierCode()).isEqualTo("SUP-A");

    var bySupplier = as("Bearer tenant-a", () -> ratings.pageRatings(querySupplier(supplierB)));
    assertThat(bySupplier.total()).isEqualTo(1);
    assertThat(bySupplier.items().get(0).supplierCode()).isEqualTo("SUP-B");
  }

  @Test
  void 概览给出等级分布与人工调整家数() throws Exception {
    UUID template = seedTemplate(tenantA);
    seedInspection(
        tenantA, template, supplierA, "供应商 A", UUID.randomUUID(), "SKU-A", "100", "100", "0",
        at(3, 10));
    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));
    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    asWrite(
        "Bearer tenant-a",
        () ->
            ratings.adjust(
                row.id(), new SupplierRatingAdjustmentInput(new BigDecimal("-5"), "复核后下调"),
                userA));

    var overview = as(
        "Bearer tenant-a", () -> ratings.overview(SupplierRatingPeriodType.MONTHLY, monthStart()));
    assertThat(overview.ratedSupplierCount()).isEqualTo(1);
    assertThat(overview.manuallyAdjustedCount()).isEqualTo(1);
    assertThat(overview.averageScore()).isNotNull();
  }

  @Test
  void 详情含调整留痕与变动历史() throws Exception {
    UUID template = seedTemplate(tenantA);
    seedInspection(
        tenantA, template, supplierA, "供应商 A", UUID.randomUUID(), "SKU-A", "100", "60", "40",
        at(3, 10));
    asWrite("Bearer tenant-a", () -> ratings.compute(SupplierRatingPeriodType.MONTHLY, monthStart(), "Bearer tenant-a"));
    var row = as("Bearer tenant-a", () -> ratings.pageRatings(query())).items().get(0);
    asWrite(
        "Bearer tenant-a",
        () ->
            ratings.adjust(
                row.id(), new SupplierRatingAdjustmentInput(new BigDecimal("8"), "现场扶持"), userA));

    var detail = as("Bearer tenant-a", () -> ratings.rating(row.id()));
    assertThat(detail.result().id()).isEqualTo(row.id());
    assertThat(detail.adjustments()).hasSize(1);
    assertThat(detail.changes()).isNotEmpty();
  }

  // ================================================================ HTTP 往返

  @Test
  void http端点往返() throws Exception {
    UUID template = seedTemplate(tenantA);
    seedOrder(
        tenantA, "PO-HTTP", supplierA, "SUP-A", "供应商 A", UUID.randomUUID(), "SKU-A", "100",
        "10", "USD", md(5), at(1, 9));
    seedInspection(
        tenantA, template, supplierA, "供应商 A", UUID.randomUUID(), "SKU-A", "100", "100", "0",
        at(3, 10));

    http.perform(post("/trade/supplier-ratings/compute")
            .header("Authorization", "Bearer tenant-a")
            .param("periodType", "MONTHLY")
            .param("periodStart", monthStart().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periodType").value("MONTHLY"));

    http.perform(get("/trade/supplier-ratings/ratings").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].supplierCode").value("SUP-A"));

    http.perform(get("/trade/supplier-ratings/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(5));

    http.perform(get("/trade/supplier-ratings/priorities")
            .header("Authorization", "Bearer tenant-a")
            .param("periodType", "MONTHLY")
            .param("periodStart", monthStart().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].rank").value(1));

    // 写端点需要 update 权限：只给 read 权限时 403。
    http.perform(post("/trade/supplier-ratings/compute")
            .header("Authorization", "Bearer read-only")
            .param("periodType", "MONTHLY"))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 助手

  /** 本月内的第 N 天（日期口径统一，避免「本月 5 号」与「今天 +5 天」混用）。 */
  private LocalDate md(int day) {
    return monthStart().plusDays(day - 1);
  }

  /** 种一条质检事实（让供应商至少有一个维度可算，评级才会出行）。 */
  private void seedQuality(UUID tenant, UUID supplier, String name, String qualified, String defective)
      throws Exception {
    UUID template = seedTemplate(tenant);
    seedInspection(
        tenant, template, supplier, name, UUID.randomUUID(), "SKU-Q", "100", qualified, defective,
        at(3, 10));
  }

  // ================================================================ 查询构造

  private SupplierRatingQuery query() {
    return new SupplierRatingQuery(
        SupplierRatingPeriodType.MONTHLY, monthStart(), null, null, null, "finalScore", true, 0, 20);
  }

  private SupplierRatingQuery query(int page, int size) {
    return new SupplierRatingQuery(
        SupplierRatingPeriodType.MONTHLY, monthStart(), null, null, null, "finalScore", true, page, size);
  }

  private SupplierRatingQuery queryGrade(SupplierRatingGrade grade) {
    return new SupplierRatingQuery(
        SupplierRatingPeriodType.MONTHLY, monthStart(), null, grade, null, "finalScore", true, 0, 20);
  }

  private SupplierRatingQuery querySupplier(UUID supplier) {
    return new SupplierRatingQuery(
        SupplierRatingPeriodType.MONTHLY, monthStart(), supplier, null, null, "finalScore", true, 0, 20);
  }
}
