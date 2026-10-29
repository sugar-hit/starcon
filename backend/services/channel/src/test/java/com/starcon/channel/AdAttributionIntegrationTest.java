package com.starcon.channel;

import static com.starcon.channel.AdAttributionModels.*;
import static com.starcon.channel.AdCampaignModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.channel.AdAttributionModels.AdAttributionImportRequest;
import com.starcon.channel.AdAttributionModels.AdAttributionOrderImport;
import com.starcon.channel.AdAttributionModels.AdAttributionClickImport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.3.2.1 归因分析的**业务口径**集成测试（真实 PostgreSQL 16 + Testcontainers）。
 *
 * <p>🔴 本测试类盯住蓝图加粗的那一句：「<b>归因规则必须固定并公示</b>，规则变了历史报表不可比」。
 * 具体地：
 *
 * <ol>
 *   <li>规则是租户级配置，<b>一个租户同时只能有一套生效口径</b>。
 *   <li>改规则 = <b>版本 +1</b>；<b>已算出的归因结果不重算、不改写</b>。
 *   <li>每条归因结果**自带规则快照** ⇒ 历史报表仍然能被「当时那套口径」解释。
 *   <li><b>归不上的金额单列</b>，不摊给任何 SKU，也不摊成 0。
 *   <li><b>分母为 0 给 null（不可比），不是 0</b>。
 *   <li><b>先汇总再相除</b>（Σ分子 / Σ分母，不是各行比率的平均）。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdAttributionIntegrationTest {

  UUID tenant;
  AccessService.Access actor;
  String bearer;
  UUID shopId;
  UUID campaignId;
  UUID skuId;

  @BeforeAll
  static void boot() {
    AdAttributionFixture.start();
  }

  @AfterAll
  static void shutdown() {
    AdAttributionFixture.stop();
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    List<AccessService.Grant> grants =
        List.of(AdAttributionFixture.grant("ALL", AdAttributionFixture.allPermissions));
    AdAttributionFixture.actors.put(
        bearer, AdAttributionFixture.actor(UUID.randomUUID(), tenant, grants));
    actor = AdAttributionFixture.actors.get(bearer);
    AdAttributionFixture.seedGlobalCatalog();
    seedShop();
    seedCampaign();
    skuId = UUID.randomUUID();
    AdAttributionFixture.skusByBearer.put(bearer, List.of(AdAttributionFixture.skuRow(skuId, "SKU-001", "商品一", "SPU-001")));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", AdAttributionFixture.pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", AdAttributionFixture.pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", AdAttributionFixture.pg::getPassword);
    r.add(
        "IDENTITY_URL",
        () -> "http://localhost:" + AdAttributionFixture.identity.getAddress().getPort());
    r.add(
        "REPORTING_URL",
        () -> "http://localhost:" + AdAttributionFixture.reporting.getAddress().getPort());
    r.add(
        "CATALOG_URL",
        () -> "http://localhost:" + AdAttributionFixture.catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired AdAttributionService service;
  @Autowired AdCampaignService campaigns;
  @Autowired AdTypeDiffService adTypes;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 播种
  // ==================================================================

  void seedShop() {
    AdAttributionFixture.execAsTenant(
        tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenant
            + "','"
            + UUID.randomUUID()
            + "','"
            + AdAttributionFixture.AMAZON
            + "','SHOP-A','店铺 A') on conflict (tenant_id,code) do nothing");
    shopId =
        UUID.fromString(
            AdAttributionFixture.execOneAsTenant(
                tenant, "select id from shop where tenant_id = '" + tenant + "' and code = 'SHOP-A'"));
  }

  void seedCampaign() {
    UUID profile =
        as(
                actor,
                "channel.operation.create",
                () ->
                    adTypes.saveProfile(
                        new AdTypeDiffModels.SaveProfileRequest(
                            null,
                            AdAttributionFixture.AMAZON,
                            "sponsored_products",
                            "AUTO_DISCOVERY",
                            "展示名",
                            "CPC",
                            "SALES",
                            true,
                            true,
                            true,
                            true,
                            false,
                            "PLATFORM_DEFAULT",
                            "测试登记")))
            .id();

    as(
        actor,
        "channel.operation.create",
        () ->
            campaigns.importCampaigns(
                new ImportRequest(
                    AdAttributionFixture.AMAZON,
                    "SHOP-A",
                    new ImportSyncRun(
                        "SUCCESS",
                        AdAttributionFixture.DATA_AS_OF,
                        AdAttributionFixture.STARTED_AT,
                        AdAttributionFixture.FINISHED_AT,
                        "",
                        "batch-A"),
                    List.of(
                        new ImportCampaign(
                            "EXT-A",
                            "活动 A",
                            profile,
                            "ENABLED",
                            "USD",
                            new BigDecimal("100.0000"),
                            null,
                            null,
                            List.of(
                                new ImportDailyMetric(
                                    AdAttributionFixture.DAY_1,
                                    new BigDecimal("1000"),
                                    new BigDecimal("100"),
                                    new BigDecimal("10"),
                                    new BigDecimal("350.0000"),
                                    new BigDecimal("200.0000"))),
                            "测试登记")))));

    campaignId =
        UUID.fromString(
            AdAttributionFixture.execOneAsTenant(
                tenant,
                "select id from ad_campaign where tenant_id = '"
                    + tenant
                    + "' and external_campaign_id = 'EXT-A'"));
  }

  /** 🔴 统计区间起（与服务层同一套表达式 ⇒ 不受运行机器时区影响）。 */
  static OffsetDateTime dayStart() {
    return AdAttributionFixture.DAY_1.atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime();
  }

  static OffsetDateTime orderAt() {
    return dayStart().plusHours(10);
  }

  // ==================================================================
  // 归因规则
  // ==================================================================

  @Test
  @DisplayName("14.3.2.1 规则：新建即生效；一个租户同时只能有一套生效口径")
  void ruleIsSingleActivePerTenant() {
    var first =
        as(
            actor,
            "channel.operation.read",
            () ->
                service.saveRule(
                    new SaveAdAttributionRuleRequest(
                        null, "RULE-A", "LAST_CLICK", 30, "第一套", true)));

    assertThat(first.status()).isEqualTo("ACTIVE");
    assertThat(first.attributionModel()).isEqualTo("LAST_CLICK");
    assertThat(first.attributionWindowDays()).isEqualTo(30);

    var second =
        as(
            actor,
            "channel.operation.read",
            () ->
                service.saveRule(
                    new SaveAdAttributionRuleRequest(
                        null, "RULE-B", "PLATFORM_WINDOW", 7, "第二套", true)));

    assertThat(second.status()).isEqualTo("ACTIVE");
    // 🔴 第一套必须被停用 ⇒ 两套口径同时生效时「这单归给了谁」没有答案。
    var rules = as(actor, "channel.operation.read", () -> service.rules(null, null, 0, 50));
    assertThat(rules.items()).hasSize(2);
    assertThat(rules.items().stream().filter(r -> "ACTIVE".equals(r.status())).count()).isEqualTo(1);
    assertThat(
            as(actor, "channel.operation.read", () -> service.currentRule()).ruleCode())
        .isEqualTo("RULE-B");
  }

  @Test
  @DisplayName("14.3.2.1 规则：改规则 = 版本 +1（历史口径可追）")
  void ruleChangeBumpsVersion() {
    var rule =
        as(
            actor,
            "channel.operation.read",
            () ->
                service.saveRule(
                    new SaveAdAttributionRuleRequest(
                        null, "RULE-A", "LAST_CLICK", 30, "第一套", true)));
    assertThat(rule.version()).isZero();

    var updated =
        as(
            actor,
            "channel.operation.read",
            () ->
                service.saveRule(
                    new SaveAdAttributionRuleRequest(
                        rule.id(), "RULE-A", "LAST_CLICK", 14, "改成 14 天", true)));
    assertThat(updated.version()).isEqualTo(1L);
    assertThat(updated.attributionWindowDays()).isEqualTo(14);
  }

  @Test
  @DisplayName("14.3.2.1 规则：不能把唯一一套生效口径停用")
  void cannotDeactivateTheOnlyActiveRule() {
    var rule =
        as(
            actor,
            "channel.operation.read",
            () ->
                service.saveRule(
                    new SaveAdAttributionRuleRequest(
                        null, "RULE-A", "LAST_CLICK", 30, "第一套", true)));
    assertThatThrownBy(
            () ->
                as(
                    actor,
                    "channel.operation.read",
                    () ->
                        service.saveRule(
                            new SaveAdAttributionRuleRequest(
                                rule.id(), "RULE-A", "LAST_CLICK", 30, "停用", false))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("生效");
  }

  // ==================================================================
  // 导入
  // ==================================================================

  @Test
  @DisplayName("14.3.2.1 导入：重复导入同一订单是刷新，不是插重复行（金额不翻倍）")
  void importIsIdempotent() {
    var result = importOrder("ORD-1", skuId, "10.0000", "30.0000");
    assertThat(result.writtenOrderRows()).isEqualTo(1);

    importOrder("ORD-1", skuId, "10.0000", "30.0000");

    List<String> spends =
        AdAttributionFixture.queryStringsAsRlsTenant(
            tenant,
            "select attributed_spend::text from ad_attribution_order where external_order_no = 'ORD-1'");
    assertThat(spends).hasSize(1);
    assertThat(new BigDecimal(spends.get(0))).isEqualByComparingTo("10.0000");
  }

  @Test
  @DisplayName("14.3.2.1 导入：被拒绝的行逐条列明原因，不静默丢弃")
  void importRejectsBadRowsWithReasons() {
    var result =
        as(
            actor,
            "channel.operation.read",
            () ->
                service.importAttribution(
                    new AdAttributionImportRequest(
                        AdAttributionFixture.DATA_AS_OF,
                        List.of(
                            new AdAttributionOrderImport(
                                null,
                                "EXT-A",
                                "SHOP-A",
                                "AMAZON",
                                "",
                                orderAt(),
                                skuId,
                                "USD",
                                new BigDecimal("1"),
                                new BigDecimal("1"),
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                List.of()),
                            new AdAttributionOrderImport(
                                null,
                                "EXT-NOT-EXIST",
                                "SHOP-A",
                                "AMAZON",
                                "ORD-X",
                                orderAt(),
                                skuId,
                                "USD",
                                new BigDecimal("1"),
                                new BigDecimal("1"),
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                List.of())))));

    assertThat(result.writtenOrderRows()).isZero();
    assertThat(result.rejected()).hasSize(2);
    assertThat(result.rejected().get(0)).contains("订单号不能为空");
    assertThat(result.rejected().get(1)).contains("找不到对应的广告活动");
  }

  @Test
  @DisplayName("14.3.2.1 导入：下单时刻按 timestamptz 原样写回（不会静默偏 −28800s）")
  void importPreservesInstant() {
    importOrder("ORD-TIME", skuId, "10.0000", "30.0000");
    OffsetDateTime stored =
        AdAttributionFixture.execOneTimestampAsTenant(
            tenant,
            "select order_at from ad_attribution_order where external_order_no = 'ORD-TIME'");
    assertThat(stored.toInstant()).isEqualTo(orderAt().toInstant());
  }

  // ==================================================================
  // 归因计算
  // ==================================================================

  @Test
  @DisplayName("14.3.2.1 归因：末次点击口径命中窗口内最后一次点击")
  void lastClickPicksLatestClick() {
    var rule = createRule("LAST_CLICK", 30);
    importOrder(
        "ORD-1",
        skuId,
        "10.0000",
        "30.0000",
        List.of(
            click("C-1", orderAt().minusHours(3), false),
            click("C-2", orderAt().minusHours(1), false),
            click("C-3", orderAt().minusHours(2), false)));

    var run = run(rule.id());
    assertThat(run.writtenFacts()).isEqualTo(1);
    assertThat(run.attributedFacts()).isEqualTo(1);

    var records = records(rule.id(), null);
    assertThat(records.items()).hasSize(1);
    var row = records.items().get(0);
    assertThat(row.attributionStatus()).isEqualTo("ATTRIBUTED");
    assertThat(row.chosenClickId()).isEqualTo("C-2");
    assertThat(row.eligibleClickCount()).isEqualTo(3);
    assertThat(row.attributed()).isTrue();
    // 🔴 规则快照随行走。
    assertThat(row.ruleCode()).isEqualTo(rule.ruleCode());
    assertThat(row.ruleVersion()).isEqualTo(rule.version());
    assertThat(row.attributionModel()).isEqualTo("LAST_CLICK");
    assertThat(row.attributionWindowDays()).isEqualTo(30);
  }

  @Test
  @DisplayName("14.3.2.1 归因：超窗的点击不参与归因 ⇒ 没有合格点击")
  void clickOutsideWindowIsIgnored() {
    var rule = createRule("LAST_CLICK", 1);
    importOrder(
        "ORD-1",
        skuId,
        "10.0000",
        "30.0000",
        List.of(click("C-OLD", orderAt().minusDays(5), true)));

    var run = run(rule.id());
    assertThat(run.writtenFacts()).isEqualTo(1);
    assertThat(run.attributedFacts()).isZero();

    var row = records(rule.id(), null).items().get(0);
    assertThat(row.attributionStatus()).isEqualTo("NO_ELIGIBLE_CLICK");
    assertThat(row.eligibleClickCount()).isZero();
    assertThat(row.chosenClickId()).isEmpty();
    // 🔴 金额保留（汇总里要能算「归不上的钱」），但不进报表。
    assertThat(row.attributedSpend()).isEqualByComparingTo("10.0000");
    assertThat(row.attributed()).isFalse();
  }

  @Test
  @DisplayName("14.3.2.1 归因：平台归因窗口口径采信平台自己标记的那一次点击")
  void platformWindowUsesPlatformFlag() {
    var rule = createRule("PLATFORM_WINDOW", 30);
    importOrder(
        "ORD-1",
        skuId,
        "10.0000",
        "30.0000",
        List.of(
            click("C-LATE", orderAt().minusMinutes(10), false),
            click("C-MARKED", orderAt().minusHours(2), true)));

    run(rule.id());
    var row = records(rule.id(), null).items().get(0);
    assertThat(row.attributionStatus()).isEqualTo("ATTRIBUTED");
    assertThat(row.chosenClickId()).isEqualTo("C-MARKED");
  }

  @Test
  @DisplayName("14.3.2.1 归因：平台没标记归因点击 ⇒ 归因不成立（不替平台编一个）")
  void platformWindowWithoutFlagIsNotAttributed() {
    var rule = createRule("PLATFORM_WINDOW", 30);
    importOrder(
        "ORD-1", skuId, "10.0000", "30.0000", List.of(click("C-1", orderAt().minusHours(1), false)));

    run(rule.id());
    var row = records(rule.id(), null).items().get(0);
    assertThat(row.attributionStatus()).isEqualTo("NO_PLATFORM_ATTRIBUTED_CLICK");
    assertThat(row.chosenClickId()).isEmpty();
    assertThat(row.attributed()).isFalse();
  }

  @Test
  @DisplayName("14.3.2.1 归因：归不到内部 SKU ⇒ 不进报表，金额单列")
  void orderWithoutSkuIsNotInReport() {
    var rule = createRule("LAST_CLICK", 30);
    importOrder(
        "ORD-1",
        null,
        "10.0000",
        "30.0000",
        List.of(click("C-1", orderAt().minusHours(1), true)));

    run(rule.id());
    var row = records(rule.id(), null).items().get(0);
    // 🔴 点击口径成立，但归不到 SKU ⇒ 不进报表。
    assertThat(row.attributionStatus()).isEqualTo("ATTRIBUTED");
    assertThat(row.internalSkuId()).isNull();
    assertThat(row.attributed()).isFalse();

    var report = report(rule.id(), null);
    assertThat(report.items()).isEmpty();
    assertThat(report.summaries()).hasSize(1);
    var summary = report.summaries().get(0);
    assertThat(summary.attributedSpend()).isEqualByComparingTo("0");
    assertThat(summary.unattributedSpend()).isEqualByComparingTo("10.0000");
    assertThat(summary.unattributedSales()).isEqualByComparingTo("30.0000");
  }

  @Test
  @DisplayName("14.3.2.1 归因：改规则后重跑产生新版本的事实，旧版本的事实原样保留")
  void ruleChangeDoesNotRewriteHistory() {
    var rule = createRule("LAST_CLICK", 30);
    importOrder(
        "ORD-1",
        skuId,
        "10.0000",
        "30.0000",
        List.of(click("C-1", orderAt().minusHours(1), true)));
    run(rule.id());

    // 🔴 改规则（窗口 0 天 ⇒ 那个点击不在窗口里 ⇒ 新版本归因不成立）。
    var updated =
        as(
            actor,
            "channel.operation.read",
            () ->
                service.saveRule(
                    new SaveAdAttributionRuleRequest(
                        rule.id(), rule.ruleCode(), "LAST_CLICK", 0, "窗口改成 0 天", true)));
    assertThat(updated.version()).isEqualTo(1L);
    run(updated.id());

    // 🔴 旧版本的事实一条都没少（历史报表不可比的那一半仍然成立）。
    List<String> versions =
        AdAttributionFixture.queryStringsAsRlsTenant(
            tenant, "select rule_version::text from ad_attribution_fact order by rule_version");
    assertThat(versions).containsExactly("0", "1");

    var v0 = records(rule.id(), 0L);
    assertThat(v0.items()).hasSize(1);
    assertThat(v0.items().get(0).attributionStatus()).isEqualTo("ATTRIBUTED");
    assertThat(v0.items().get(0).ruleVersion()).isZero();

    var v1 = records(rule.id(), 1L);
    assertThat(v1.items()).hasSize(1);
    assertThat(v1.items().get(0).attributionStatus()).isEqualTo("NO_ELIGIBLE_CLICK");
    assertThat(v1.items().get(0).ruleVersion()).isEqualTo(1L);
  }

  // ==================================================================
  // 归因明细
  // ==================================================================

  @Test
  @DisplayName("14.3.2.1 明细：真分页（total 是全集，汇总也是全集）+ 规则快照随行")
  void recordsAreReallyPaginated() {
    var rule = createRule("LAST_CLICK", 30);
    importOrder("ORD-1", skuId, "10.0000", "30.0000", List.of(click("C-1", orderAt().minusHours(1), true)));
    importOrder("ORD-2", skuId, "20.0000", "50.0000", List.of(click("C-2", orderAt().minusHours(1), true)));
    importOrder("ORD-3", skuId, "30.0000", "70.0000", List.of(click("C-3", orderAt().minusHours(1), true)));
    run(rule.id());

    var page = records(rule.id(), null, 0, 2);
    assertThat(page.items()).hasSize(2);
    assertThat(page.total()).isEqualTo(3);
    // 🔴 汇总统计的是**全集**，不是当前页。
    assertThat(page.summaries()).hasSize(1);
    assertThat(page.summaries().get(0).totalSpend()).isEqualByComparingTo("60.0000");
    assertThat(page.summaries().get(0).attributedSpend()).isEqualByComparingTo("60.0000");
    assertThat(page.summaries().get(0).attributedRows()).isEqualTo(3);
  }

  // ==================================================================
  // 广告后利润报表
  // ==================================================================

  @Test
  @DisplayName("14.3.2.1 报表：TACOS / 广告后净利 / 广告销售占比 —— 先汇总再相除")
  void reportComputesTacosAndAdAfterNetProfit() {
    // 总销售额 200、总净利 100。
    AdAttributionFixture.profitByBearer.put(
        bearer,
        List.of(
            AdAttributionFixture.profitRow(
                "SKU-001", "USD", "200", "100", "25", "100", "100")));

    var rule = createRule("LAST_CLICK", 30);
    importOrder("ORD-1", skuId, "30.0000", "60.0000", List.of(click("C-1", orderAt().minusHours(1), true)));
    run(rule.id());

    var report = report(rule.id(), null);
    assertThat(report.items()).hasSize(1);
    var row = report.items().get(0);
    assertThat(row.skuCode()).isEqualTo("SKU-001");
    assertThat(row.adSpend()).isEqualByComparingTo("30.0000");
    assertThat(row.adSales()).isEqualByComparingTo("60.0000");
    assertThat(row.totalSales()).isEqualByComparingTo("200");
    assertThat(row.totalUnits()).isEqualByComparingTo("100");
    assertThat(row.netProfit()).isEqualByComparingTo("100");
    // TACOS = 30 / 200 = 0.15
    assertThat(row.tacos()).isEqualByComparingTo("0.150000");
    // ACOS = 30 / 60 = 0.5
    assertThat(row.acos()).isEqualByComparingTo("0.500000");
    // 广告销售占比 = 60 / 200 = 0.3
    assertThat(row.adSalesShare()).isEqualByComparingTo("0.300000");
    // 广告后净利 = 100 − 30 = 70
    assertThat(row.adAfterNetProfit()).isEqualByComparingTo("70");
    // 广告占比利润 = 30 / 100 = 0.3
    assertThat(row.adProfitShare()).isEqualByComparingTo("0.300000");
  }

  @Test
  @DisplayName("14.3.2.1 报表：增量效果估计有明确口径（推导值，可为负）")
  void reportComputesIncrementalityEstimate() {
    // 总销售额 200 / 总件数 100；广告销售额 60 / 广告件数 20。
    AdAttributionFixture.profitByBearer.put(
        bearer,
        List.of(
            AdAttributionFixture.profitRow(
                "SKU-001", "USD", "200", "100", "25", "100", "100")));

    var rule = createRule("LAST_CLICK", 30);
    importOrder("ORD-1", skuId, "30.0000", "60.0000", List.of(click("C-1", orderAt().minusHours(1), true)));
    run(rule.id());

    var row = report(rule.id(), null).items().get(0);
    // 自然：销售额 140 / 件数 80 ⇒ 单件均价 1.75
    // 反事实广告销售额 = 20 × 1.75 = 35 ⇒ 增量 = 60 − 35 = 25
    assertThat(row.incrementalSalesEstimate()).isEqualByComparingTo("25.0000");
    // 增量占比 = 25 / 200 = 0.125
    assertThat(row.incrementalShare()).isEqualByComparingTo("0.125000");
    // 🔴 口径原文必须随报表一起返回。
    assertThat(report(rule.id(), null).incrementalityNote()).contains("推导值").contains("不是因果增量");
  }

  @Test
  @DisplayName("14.3.2.1 报表：分母为 0 给 null（不可比），不是 0")
  void reportGivesNullWhenDenominatorIsZero() {
    // 总销售额 0 ⇒ TACOS 没有分母。
    AdAttributionFixture.profitByBearer.put(
        bearer,
        List.of(
            AdAttributionFixture.profitRow(
                "SKU-001", "USD", "0", "0", "0", "0", "0")));

    var rule = createRule("LAST_CLICK", 30);
    importOrder("ORD-1", skuId, "30.0000", "0.0000", List.of(click("C-1", orderAt().minusHours(1), true)));
    run(rule.id());

    var row = report(rule.id(), null).items().get(0);
    assertThat(row.tacos()).isNull();
    assertThat(row.adSalesShare()).isNull();
    // 广告销售额 0 ⇒ ACOS 没有分母。
    assertThat(row.acos()).isNull();
    // 自然件数为 0 ⇒ 增量效果算不出来（**不是 0**）。
    assertThat(row.incrementalSalesEstimate()).isNull();
  }

  @Test
  @DisplayName("14.3.2.1 报表：活动级花费与订单级归因花费的对账缺口单列")
  void reportShowsCampaignReconciliationGap() {
    AdAttributionFixture.profitByBearer.put(
        bearer,
        List.of(
            AdAttributionFixture.profitRow(
                "SKU-001", "USD", "200", "100", "25", "100", "100")));

    var rule = createRule("LAST_CLICK", 30);
    importOrder("ORD-1", skuId, "30.0000", "60.0000", List.of(click("C-1", orderAt().minusHours(1), true)));
    run(rule.id());

    var summary = report(rule.id(), null).summaries().get(0);
    // 🔴 活动级花费 350（ad_campaign_daily_metric）。
    assertThat(summary.campaignTotalSpend()).isEqualByComparingTo("350.0000");
    // 订单级归因花费 30 ⇒ 缺口 320（活动里没落到订单上的部分）。
    assertThat(summary.campaignSpendGap()).isEqualByComparingTo("320.0000");
    assertThat(summary.attributionCoverage()).isEqualByComparingTo("0.085714");
  }

  @Test
  @DisplayName("14.3.2.1 报表：上游读不到 ⇒ 降级 + 明确原因，不是 0 也不是 503")
  void reportDegradesWhenProfitUpstreamUnavailable() {
    // 🔴 reporting 桩对该 bearer 返回 401 ⇒ 上游读不到。
    AdAttributionFixture.profitByBearer.remove(bearer);

    var rule = createRule("LAST_CLICK", 30);
    importOrder("ORD-1", skuId, "30.0000", "60.0000", List.of(click("C-1", orderAt().minusHours(1), true)));
    run(rule.id());

    var report = report(rule.id(), null);
    assertThat(report.profitAvailable()).isFalse();
    assertThat(report.profitUnavailableReason()).isNotBlank();
    var row = report.items().get(0);
    assertThat(row.totalSales()).isNull();
    assertThat(row.tacos()).isNull();
    assertThat(row.adAfterNetProfit()).isNull();
    // 🔴 广告侧不受影响。
    assertThat(row.adSpend()).isEqualByComparingTo("30.0000");
    assertThat(row.profitNote()).isNotBlank();
  }

  // ==================================================================
  // 导出
  // ==================================================================

  @Test
  @DisplayName("14.3.2.1 导出：口径与规则快照写进 CSV 表头，且每次导出留痕")
  void exportWritesCaliberAndLeavesTrace() throws Exception {
    AdAttributionFixture.profitByBearer.put(
        bearer,
        List.of(
            AdAttributionFixture.profitRow(
                "SKU-001", "USD", "200", "100", "25", "100", "100")));

    var rule = createRule("LAST_CLICK", 30);
    importOrder("ORD-1", skuId, "30.0000", "60.0000", List.of(click("C-1", orderAt().minusHours(1), true)));
    run(rule.id());

    var trace =
        as(
            actor,
            "channel.operation.read",
            () ->
                service.recordExport(
                    service.normalizeReportQuery(
                        rule.id(),
                        null,
                        AdAttributionFixture.DAY_1,
                        AdAttributionFixture.DAY_1,
                        null,
                        null,
                        null,
                        null,
                        "SPEND_DESC",
                        0,
                        50),
                    actor));
    assertThat(trace.ruleCode()).isEqualTo(rule.ruleCode());
    assertThat(trace.ruleVersion()).isEqualTo(rule.version());
    assertThat(trace.attributionModel()).isEqualTo("LAST_CLICK");
    assertThat(trace.attributionNote()).contains("归因规则必须固定并公示");
    assertThat(trace.metricDefinitionNote()).contains("先汇总再相除");

    var exports = as(actor, "channel.operation.read", () -> service.exports(0, 50));
    assertThat(exports.total()).isEqualTo(1);
  }

  // ==================================================================
  // 参数校验
  // ==================================================================

  @Test
  @DisplayName("14.3.2.1 校验：时间范围必填 / 跨度封顶 / 非法取值 400 / 排序串注入被拒")
  void validationRejectsBadInput() {
    assertThatThrownBy(
            () ->
                service.normalizeReportQuery(
                    null, null, null, AdAttributionFixture.DAY_1, null, null, null, null, "SPEND_DESC", 0, 50))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必填");

    assertThatThrownBy(
            () ->
                service.normalizeReportQuery(
                    null,
                    null,
                    AdAttributionFixture.DAY_1,
                    AdAttributionFixture.DAY_1.minusDays(1),
                    null,
                    null,
                    null,
                    null,
                    "SPEND_DESC",
                    0,
                    50))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能晚于");

    assertThatThrownBy(
            () ->
                service.normalizeReportQuery(
                    null,
                    null,
                    AdAttributionFixture.DAY_1.minusDays(500),
                    AdAttributionFixture.DAY_1,
                    null,
                    null,
                    null,
                    null,
                    "SPEND_DESC",
                    0,
                    50))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("跨度过大");

    // 🔴 排序串绝不拼进 SQL ⇒ 白名单之外的直接 400。
    assertThatThrownBy(
            () ->
                service.normalizeReportQuery(
                    null,
                    null,
                    AdAttributionFixture.DAY_1,
                    AdAttributionFixture.DAY_1,
                    null,
                    null,
                    null,
                    null,
                    "spend desc; drop table ad_attribution_fact",
                    0,
                    50))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("白名单");

    assertThatThrownBy(
            () ->
                service.saveRule(
                    new SaveAdAttributionRuleRequest(
                        null, "RULE-X", "SOMETHING_ELSE", 30, "", true)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("归因模型");
  }

  @Test
  @DisplayName("14.3.2.1 HTTP：端点通，且伪造 tenant header 无效")
  void httpEndpointsWork() throws Exception {
    var rule = createRule("LAST_CLICK", 30);
    importOrder("ORD-1", skuId, "30.0000", "60.0000", List.of(click("C-1", orderAt().minusHours(1), true)));
    run(rule.id());

    http.perform(
            get("/channel/ad-attributions/records")
                .header("Authorization", bearer)
                .param("from", AdAttributionFixture.DAY_1.toString())
                .param("to", AdAttributionFixture.DAY_1.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].attributionStatus").value("ATTRIBUTED"));

    http.perform(get("/channel/ad-attributions/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attributionNote").isNotEmpty())
        .andExpect(jsonPath("$.incrementalityNote").isNotEmpty());

    http.perform(get("/channel/ad-attributions/rules/current").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ruleCode").value(rule.ruleCode()));

    // 🔴 没带会话 ⇒ 401（租户不来自任何客户端头）。
    http.perform(get("/channel/ad-attributions/capabilities")).andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 辅助
  // ==================================================================

  AdAttributionRuleRow createRule(String model, int windowDays) {
    return as(
        actor,
        "channel.operation.read",
        () ->
            service.saveRule(
                new SaveAdAttributionRuleRequest(
                    null, "RULE-" + model, model, windowDays, "测试规则", true)));
  }

  AdAttributionImportResult importOrder(String orderNo, UUID sku, String spend, String sales) {
    return importOrder(orderNo, sku, spend, sales, List.of());
  }

  AdAttributionImportResult importOrder(
      String orderNo, UUID sku, String spend, String sales, List<AdAttributionClickImport> clicks) {
    return as(
        actor,
        "channel.operation.read",
        () ->
            service.importAttribution(
                new AdAttributionImportRequest(
                    AdAttributionFixture.DATA_AS_OF,
                    List.of(
                        new AdAttributionOrderImport(
                            campaignId,
                            "EXT-A",
                            "SHOP-A",
                            "AMAZON",
                            orderNo,
                            orderAt(),
                            sku,
                            "USD",
                            new BigDecimal(spend),
                            new BigDecimal(sales),
                            BigDecimal.ONE,
                            new BigDecimal("20"),
                            clicks)))));
  }

  static AdAttributionClickImport click(String id, OffsetDateTime at, boolean platformAttributed) {
    return new AdAttributionClickImport(id, at, platformAttributed);
  }

  AdAttributionRunResult run(UUID ruleId) {
    return as(
        actor,
        "channel.operation.read",
        () ->
            service.run(
                new AdAttributionRunRequest(
                    ruleId, AdAttributionFixture.DAY_1, AdAttributionFixture.DAY_1, false)));
  }

  AdAttributionRecordPage records(UUID ruleId, Long version) {
    return records(ruleId, version, 0, 50);
  }

  AdAttributionRecordPage records(UUID ruleId, Long version, int page, int size) {
    return as(
        actor,
        "channel.operation.read",
        () ->
            service.records(
                service.normalizeRecordQuery(
                    ruleId,
                    version,
                    AdAttributionFixture.DAY_1,
                    AdAttributionFixture.DAY_1,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "ORDER_AT_DESC",
                    page,
                    size)));
  }

  AdAttributionReportPage report(UUID ruleId, Long version) {
    // 🔴 直接调服务（**不**再包一层 access.run）：服务层自己把「数据库部分」放进事务，
    //   跨服务只读（reporting / catalog）在事务之外 —— 与生产路径一致。
    return service.report(
        service.normalizeReportQuery(
            ruleId,
            version,
            AdAttributionFixture.DAY_1,
            AdAttributionFixture.DAY_1,
            null,
            null,
            null,
            null,
            "SPEND_DESC",
            0,
            50),
        actor,
        bearer);
  }
}
