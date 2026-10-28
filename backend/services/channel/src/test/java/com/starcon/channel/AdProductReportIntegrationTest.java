package com.starcon.channel;

import static com.starcon.channel.AdCampaignModels.*;
import static com.starcon.channel.AdProductReportModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 14.3.1.3 商品维度报表。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>两级等分的分摊规则</b>：活动 → Listing → SKU，每一级都等分，且**规则公示**。
 *   <li><b>「直接归属」与「等分摊销」分开标记</b>（DIRECT / ALLOCATED_EVENLY / MIXED）。
 *   <li><b>归不到商品的广告花费不摊给任何商品</b>（unattributed 单列，且与总额对得上）。
 *   <li><b>先汇总再相除</b>：不是各行比率的平均。
 *   <li><b>分母为 0 给空值</b>：不可比 ≠ 0。
 *   <li><b>利润归不到唯一商品时不摊</b>：LISTING 维度挂了多个 SKU ⇒ 利润列空 + 原因。
 *   <li><b>真分页真聚合</b>：分页真的分页，汇总统计的是全集而不是当前页。
 *   <li><b>不同币种不合并</b>：没有汇率源 ⇒ 汇总按币种拆开。
 *   <li><b>上游读不到时降级并说明原因</b>（不是空集合冒充「上游没有数据」）。
 *   <li><b>timestamptz 往返</b>断言<b>写回值</b>逐字相等（AGENTS.md 要求）。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdProductReportIntegrationTest {

  /** 🔴 每个用例一对**全新的租户**（共用租户会让前一个用例的数据被后一个用例数进去）。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  @BeforeAll
  static void boot() {
    AdProductReportFixture.start();
  }

  @AfterAll
  static void shutdown() {
    // 🔴 先停外部桩、再让容器被 ryuk 回收，避免容器泄漏把机器压进 swap。
    AdProductReportFixture.stop();
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants =
        List.of(AdProductReportFixture.grant("ALL", AdProductReportFixture.allPermissions));
    AdProductReportFixture.actors.put(
        bearerA, AdProductReportFixture.actor(UUID.randomUUID(), a, grants));
    AdProductReportFixture.actors.put(
        bearerB, AdProductReportFixture.actor(UUID.randomUUID(), b, grants));
    actorA = AdProductReportFixture.actors.get(bearerA);
    actorB = AdProductReportFixture.actors.get(bearerB);
    // 🔴 播种平台默认映射（全局码表）：广告类型的归一口径真相在 2.1.2.1。
    AdProductReportFixture.seedGlobalCatalog();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", AdProductReportFixture.pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", AdProductReportFixture.pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", AdProductReportFixture.pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + AdProductReportFixture.identity.getAddress().getPort());
    r.add(
        "REPORTING_URL",
        () -> "http://localhost:" + AdProductReportFixture.reporting.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + AdProductReportFixture.catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired AdProductReportService service;
  @Autowired AdCampaignService campaigns;
  @Autowired AdCampaignDetailService details;
  @Autowired AdTypeDiffService adTypes;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  String seedShop(UUID tenant, String code) {
    AdProductReportFixture.execAsTenant(
        tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenant + "','"
            + UUID.randomUUID()
            + "','"
            + AdProductReportFixture.AMAZON
            + "','"
            + code
            + "','测试店铺 "
            + code
            + "') on conflict (tenant_id,code) do nothing");
    return code;
  }

  UUID seedListing(UUID tenant, String shopCode, String externalItemId, String title) {
    UUID shopId =
        UUID.fromString(
            AdProductReportFixture.execOneAsTenant(
                tenant,
                "select id from shop where tenant_id = '"
                    + tenant
                    + "' and code = '"
                    + shopCode
                    + "'"));
    UUID id = UUID.randomUUID();
    AdProductReportFixture.execAsTenant(
        tenant,
        "insert into listing (id,tenant_id,shop_id,external_item_id,title,status) values ('"
            + id
            + "','"
            + tenant
            + "','"
            + shopId
            + "','"
            + externalItemId
            + "','"
            + title
            + "','ON_SALE')");
    return id;
  }

  /** 挂一个内部 SKU 变体（4.1.1.1 的映射现状位）。 */
  void seedVariant(UUID tenant, UUID listingId, String externalVariantId, UUID internalSkuId) {
    AdProductReportFixture.execAsTenant(
        tenant,
        "insert into listing_variant (id,tenant_id,listing_id,external_variant_id,attributes,"
            + " mapping_status,internal_sku_id,created_at,updated_at) values ('"
            + UUID.randomUUID()
            + "','"
            + tenant
            + "','"
            + listingId
            + "','"
            + externalVariantId
            + "','{}'::jsonb,'MAPPED','"
            + internalSkuId
            + "',now(),now())");
  }

  UUID saveProfile(AccessService.Access who, String external, String internal) {
    return as(
            who,
            "channel.operation.create",
            () ->
                adTypes.saveProfile(
                    new AdTypeDiffModels.SaveProfileRequest(
                        null,
                        AdProductReportFixture.AMAZON,
                        external,
                        internal,
                        external + " 展示名",
                        "CPC",
                        "SALES",
                        true,
                        true,
                        true,
                        true,
                        false,
                        "UNMAPPED".equals(internal) ? "UNMAPPED" : "PLATFORM_DEFAULT",
                        "测试登记")))
        .id();
  }

  /** 登记一个活动 + 当日表现。 */
  UUID seedCampaign(
      AccessService.Access who,
      String shop,
      UUID profileId,
      String externalId,
      String currency,
      String impressions,
      String clicks,
      String orders,
      String spend,
      String sales) {
    as(
        who,
        "channel.operation.create",
        () ->
            campaigns.importCampaigns(
                new ImportRequest(
                    AdProductReportFixture.AMAZON,
                    shop,
                    new ImportSyncRun(
                        "SUCCESS",
                        AdProductReportFixture.DATA_AS_OF,
                        AdProductReportFixture.STARTED_AT,
                        AdProductReportFixture.FINISHED_AT,
                        "",
                        "batch-" + externalId),
                    List.of(
                        new ImportCampaign(
                            externalId,
                            "活动 " + externalId,
                            profileId,
                            "ENABLED",
                            currency,
                            new BigDecimal("100.0000"),
                            null,
                            null,
                            List.of(
                                new ImportDailyMetric(
                                    AdProductReportFixture.DAY_1,
                                    new BigDecimal(impressions),
                                    new BigDecimal(clicks),
                                    new BigDecimal(orders),
                                    new BigDecimal(spend),
                                    new BigDecimal(sales))),
                            "测试登记")))));
    return UUID.fromString(
        AdProductReportFixture.execOneAsTenant(
            who.tenantId(),
            "select id from ad_campaign where tenant_id = '"
                + who.tenantId()
                + "' and external_campaign_id = '"
                + externalId
                + "'"));
  }

  /** 登记「活动 → 投放商品」的投放关系（14.1.1.2 的真实写入口）。 */
  void bindProducts(
      AccessService.Access who, UUID campaignId, String groupExternalId, List<UUID> listingIds) {
    as(
        who,
        "channel.operation.create",
        () ->
            details.importStructure(
                campaignId.toString(),
                new AdCampaignDetailModels.AdCampaignStructureImportRequest(
                    AdProductReportFixture.STRUCTURE_AS_OF,
                    List.of(
                        new AdCampaignDetailModels.AdCampaignGroupImport(
                            groupExternalId,
                            "组 " + groupExternalId,
                            "ENABLED",
                            new BigDecimal("1.5000"),
                            "USD",
                            new BigDecimal("50.0000"),
                            "AUTO",
                            listingIds.stream()
                                .map(
                                    id ->
                                        new AdCampaignDetailModels.AdCampaignProductImport(
                                            id, null, null))
                                .toList(),
                            List.of())),
                    List.of())));
  }

  /**
   * 播种标准数据集：
   *
   * <ul>
   *   <li>C1 只投 L1 ⇒ L1 的数字里有一部分是**平台原值**（DIRECT 那一半）。
   *   <li>C2 投 L1 + L2 ⇒ **等分**：L1 拿一半、L2 拿一半。
   *   <li>C3 不投任何商品 ⇒ **归不到任何商品**。
   *   <li>L1 挂 1 个 SKU、L2 挂 2 个 SKU。
   * </ul>
   */
  StandardData seedStandard(AccessService.Access who, UUID tenant, String suffix) {
    lastData = null;
    String shop = seedShop(tenant, "SHOP-" + suffix);
    UUID profile = saveProfile(who, "sponsored_products", "AUTO_DISCOVERY");

    UUID l1 = seedListing(tenant, shop, "ASIN-" + suffix + "-1", "投放商品一");
    UUID l2 = seedListing(tenant, shop, "ASIN-" + suffix + "-2", "投放商品二");
    UUID sku1 = UUID.randomUUID();
    UUID sku2 = UUID.randomUUID();
    UUID sku3 = UUID.randomUUID();
    seedVariant(tenant, l1, "V-1", sku1);
    seedVariant(tenant, l2, "V-2", sku2);
    seedVariant(tenant, l2, "V-3", sku3);

    UUID c1 =
        seedCampaign(
            who, shop, profile, "EXT-" + suffix + "-C1", "USD", "1000", "50", "5", "100", "500");
    UUID c2 =
        seedCampaign(
            who, shop, profile, "EXT-" + suffix + "-C2", "USD", "2000", "100", "10", "200", "800");
    UUID c3 =
        seedCampaign(
            who, shop, profile, "EXT-" + suffix + "-C3", "USD", "100", "10", "1", "50", "100");

    bindProducts(who, c1, "GRP-" + suffix + "-1", List.of(l1));
    bindProducts(who, c2, "GRP-" + suffix + "-2", List.of(l1, l2));
    // 🔴 C3 **不登记任何投放商品** ⇒ 它的花费归不到任何商品。

    // 利润侧与商品主数据的桩数据
    AdProductReportFixture.skusByBearer.put(
        bearerOf(who),
        List.of(
            AdProductReportFixture.skuRow(sku1, "SKU-1", "商品一", "SPU-1"),
            AdProductReportFixture.skuRow(sku2, "SKU-2", "商品二", "SPU-2"),
            AdProductReportFixture.skuRow(sku3, "SKU-3", "商品三", "SPU-2")));
    AdProductReportFixture.profitByBearer.put(
        bearerOf(who),
        List.of(
            AdProductReportFixture.profitRow(
                "SKU-1", "USD", "1200.0000", "3", "50.0000", "400.0000", "500.0000"),
            AdProductReportFixture.profitRow(
                "SKU-2", "USD", "400.0000", "1", "20.0000", "150.0000", "150.0000"),
            AdProductReportFixture.profitRow(
                "SKU-3", "USD", "200.0000", "1", "10.0000", "100.0000", "80.0000")));
    return lastData =
        new StandardData(l1, l2, sku1, sku2, sku3, c1, c2, c3);
  }

  /** 标准数据（供断言用）。 */
  private StandardData lastData;

  record StandardData(
      UUID l1, UUID l2, UUID sku1, UUID sku2, UUID sku3, UUID c1, UUID c2, UUID c3) {}

  static String bearerOf(AccessService.Access who) {
    for (Map.Entry<String, AccessService.Access> e : AdProductReportFixture.actors.entrySet()) {
      if (e.getValue().tenantId().equals(who.tenantId())) return e.getKey();
    }
    throw new IllegalStateException("找不到该成员的会话");
  }

  /** 跑一次报表查询（走服务层的归一化与校验）。 */
  AdProductReportPage report(
      AccessService.Access who, String dimension, LocalDate from, LocalDate to, int page, int size) {
    return service.report(
        service.normalize(from, to, dimension, "SPEND_DESC", null, null, null, null, page, size),
        who,
        bearerOf(who));
  }

  // ==================================================================
  // 1. 能力声明
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 能力声明：单量口径 / 归因 / 跨币种统一显式 available=false")
  void capabilitiesDeclareUndeliveredParts() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    Map<String, AdProductCapabilityDimension> dims =
        caps.dimensions().stream()
            .collect(java.util.stream.Collectors.toMap(AdProductCapabilityDimension::dimension, d -> d));

    assertThat(dims.get("listing_dimension").available()).isTrue();
    assertThat(dims.get("sku_dimension").available()).isTrue();
    assertThat(dims.get("spu_dimension").available()).isTrue();
    assertThat(dims.get("attribution").available()).isTrue();
    assertThat(dims.get("profit_side").available()).isTrue();

    // 🔴 单量口径的广告销售占比：没有分母 ⇒ 显式未交付 + 责任模块 + 影响说明。
    assertThat(dims.get("ad_order_share").available()).isFalse();
    assertThat(dims.get("ad_order_share").ownerModule()).contains("13.4.1.1");
    assertThat(dims.get("ad_order_share").impact()).contains("没有分母");

    // 🔴 归因归 14.3.2.1（分摊 ≠ 归因）。
    assertThat(dims.get("attribution_14_3_2_1").available()).isFalse();
    assertThat(dims.get("attribution_14_3_2_1").ownerModule()).contains("14.3.2.1");

    // 🔴 没有汇率源 ⇒ 不跨币种合并。
    assertThat(dims.get("multi_currency_unification").available()).isFalse();

    // 🔴 归因规则必须**公示**。
    assertThat(caps.attributionNote()).contains("等分").contains("不摊给任何商品");
    assertThat(caps.metricDefinitions()).isNotEmpty();
  }

  // ==================================================================
  // 2. LISTING 维度：两级等分
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 LISTING 维度：活动 → Listing 等分，且「直接 / 等分」分开标记")
  void listingDimensionAllocatesEvenlyAndMarksAttribution() {
    seedStandard(actorA, a, "A");

    AdProductReportPage page = report(actorA, "LISTING", AdProductReportFixture.DAY_1,
        AdProductReportFixture.DAY_1, 0, 50);

    assertThat(page.items()).hasSize(2);
    AdProductReportRow l1 = page.items().stream()
        .filter(r -> r.listingId().equals(seededL1().toString()))
        .findFirst()
        .orElseThrow();
    AdProductReportRow l2 = page.items().stream()
        .filter(r -> r.listingId().equals(seededL2().toString()))
        .findFirst()
        .orElseThrow();

    // 🔴 L1 = C1 的 100（直接归属）+ C2 的 200 ÷ 2 = 100（等分）= 200。
    assertThat(l1.spend()).isEqualByComparingTo("200.0000");
    assertThat(l1.sales()).isEqualByComparingTo("900.0000");
    assertThat(l1.impressions()).isEqualByComparingTo("2000");
    assertThat(l1.clicks()).isEqualByComparingTo("100");
    assertThat(l1.orders()).isEqualByComparingTo("10");
    // 🔴 一半直接、一半摊销 ⇒ 必须标 MIXED（标成别的会把「有平台原值」这件事藏起来）。
    assertThat(l1.attributionMode()).isEqualTo("MIXED");
    assertThat(l1.attributionLabel()).contains("部分直接");

    // 🔴 L2 = C2 的 200 ÷ 2 = 100（纯等分）。
    assertThat(l2.spend()).isEqualByComparingTo("100.0000");
    assertThat(l2.sales()).isEqualByComparingTo("400.0000");
    assertThat(l2.attributionMode()).isEqualTo("ALLOCATED_EVENLY");
    // 🔴 ROAS = Σ广告销售额 / Σ广告花费 = 900 / 200 = 4.5（口径定义里有 ROAS ⇒ 必须有值）。
    assertThat(l1.roas()).isEqualByComparingTo("4.5");
    assertThat(l2.roas()).isEqualByComparingTo("4");
  }

  // 记住标准数据里的 listing id（供断言用）。
  private UUID seededL1() {
    return lastData.l1();
  }

  private UUID seededL2() {
    return lastData.l2();
  }

  // ==================================================================
  // 3. 归不到商品的花费不摊给任何商品
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 归不到商品的广告花费单列，且与范围内总额对得上")
  void unattributedSpendIsNotSpreadToAnyProduct() {
    seedStandard(actorA, a, "A");

    AdProductReportPage page = report(actorA, "LISTING", AdProductReportFixture.DAY_1,
        AdProductReportFixture.DAY_1, 0, 50);

    AdProductReportSummary s = page.summaries().get(0);
    // 🔴 行合计 = 200 + 100 = 300（C3 的 50 没有摊给任何商品）。
    assertThat(s.totalSpend()).isEqualByComparingTo("300.0000");
    // 🔴 范围内总额 = 100 + 200 + 50 = 350。
    assertThat(s.campaignTotalSpend()).isEqualByComparingTo("350.0000");
    // 🔴 差额 = 50 ⇒ 那笔「没投任何商品」的广告花费**没有被摊成 0**。
    assertThat(s.unattributedSpend()).isEqualByComparingTo("50.0000");
    assertThat(s.campaignTotalSales()).isEqualByComparingTo("1400.0000");
    assertThat(s.totalSales()).isEqualByComparingTo("1300.0000");
    assertThat(s.unattributedSales()).isEqualByComparingTo("100.0000");
    // 🔴 汇总 ROAS = Σ广告销售额 / Σ广告花费 = 1300 / 300（**先汇总再相除**，不是各行 ROAS 的平均）。
    assertThat(s.roas()).isEqualByComparingTo("4.333333");
  }

  // ==================================================================
  // 4. 先汇总再相除
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 汇总是「先汇总再相除」，不是各行比率的平均")
  void summaryDividesAfterSumming() {
    seedStandard(actorA, a, "A");

    AdProductReportPage page = report(actorA, "LISTING", AdProductReportFixture.DAY_1,
        AdProductReportFixture.DAY_1, 0, 50);
    AdProductReportSummary s = page.summaries().get(0);

    // 🔴 Σ花费 / Σ销售额 = 300 / 1300（**不是** (200/900 + 100/400) ÷ 2）。
    assertThat(s.acos()).isEqualByComparingTo(new BigDecimal("300").divide(new BigDecimal("1300"), 6,
        java.math.RoundingMode.HALF_UP));
    assertThat(s.acos()).isNotEqualByComparingTo(new BigDecimal("250").divide(new BigDecimal("650"), 6,
        java.math.RoundingMode.HALF_UP));
    // 🔴 CTR = Σ点击 / Σ曝光 = 150 / 3000。
    assertThat(s.ctr()).isEqualByComparingTo("0.05");
  }

  // ==================================================================
  // 5. SKU 维度：Listing → SKU 再等分
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 SKU 维度：Listing → SKU 再等分，数字守恒")
  void skuDimensionSplitsListingEvenly() {
    seedStandard(actorA, a, "A");

    AdProductReportPage page = report(actorA, "SKU", AdProductReportFixture.DAY_1,
        AdProductReportFixture.DAY_1, 0, 50);

    assertThat(page.items()).hasSize(3);
    Map<String, AdProductReportRow> byCode =
        page.items().stream()
            .collect(java.util.stream.Collectors.toMap(AdProductReportRow::skuCode, r -> r));

    // 🔴 L1 只挂 1 个 SKU ⇒ 全额归它。
    assertThat(byCode.get("SKU-1").spend()).isEqualByComparingTo("200.0000");
    assertThat(byCode.get("SKU-1").sales()).isEqualByComparingTo("900.0000");
    // 🔴 L2 挂 2 个 SKU ⇒ 各得一半。
    assertThat(byCode.get("SKU-2").spend()).isEqualByComparingTo("50.0000");
    assertThat(byCode.get("SKU-2").sales()).isEqualByComparingTo("200.0000");
    assertThat(byCode.get("SKU-3").spend()).isEqualByComparingTo("50.0000");
    assertThat(byCode.get("SKU-3").sales()).isEqualByComparingTo("200.0000");

    // 🔴 分摊不改变总量：SKU 维度的合计 = LISTING 维度的合计。
    assertThat(page.summaries().get(0).totalSpend()).isEqualByComparingTo("300.0000");
    assertThat(page.summaries().get(0).totalSales()).isEqualByComparingTo("1300.0000");
  }

  // ==================================================================
  // 6. SPU 维度：SKU 归并
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 SPU 维度：SKU 归并成 SPU（相加，不是再分摊一次）")
  void spuDimensionMergesSkus() {
    seedStandard(actorA, a, "A");

    AdProductReportPage page = report(actorA, "SPU", AdProductReportFixture.DAY_1,
        AdProductReportFixture.DAY_1, 0, 50);

    assertThat(page.items()).hasSize(2);
    Map<String, AdProductReportRow> bySpu =
        page.items().stream()
            .collect(java.util.stream.Collectors.toMap(AdProductReportRow::spuCode, r -> r));

    assertThat(bySpu.get("SPU-1").spend()).isEqualByComparingTo("200.0000");
    assertThat(bySpu.get("SPU-1").sales()).isEqualByComparingTo("900.0000");
    // 🔴 SPU-2 = SKU-2 + SKU-3 = 50 + 50 = 100（**相加**，分摊已经在 SKU 那一级做过了）。
    assertThat(bySpu.get("SPU-2").spend()).isEqualByComparingTo("100.0000");
    assertThat(bySpu.get("SPU-2").sales()).isEqualByComparingTo("400.0000");
    assertThat(page.summaries().get(0).totalSpend()).isEqualByComparingTo("300.0000");
  }

  // ==================================================================
  // 7. 利润归不到唯一商品时不摊
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 利润归不到唯一 listing 时不摊：利润列空 + 写明原因")
  void profitColumnsFollowUniqueAttribution() {
    seedStandard(actorA, a, "A");

    AdProductReportPage page = report(actorA, "LISTING", AdProductReportFixture.DAY_1,
        AdProductReportFixture.DAY_1, 0, 50);

    AdProductReportRow l1 = page.items().stream()
        .filter(r -> r.listingId().equals(lastData.l1().toString()))
        .findFirst()
        .orElseThrow();
    AdProductReportRow l2 = page.items().stream()
        .filter(r -> r.listingId().equals(lastData.l2().toString()))
        .findFirst()
        .orElseThrow();

    // 🔴 L1 只挂 1 个 SKU，且该 SKU 只挂在 L1 上 ⇒ 利润**精确**归它。
    assertThat(l1.totalSales()).isEqualByComparingTo("1200.0000");
    assertThat(l1.netProfit()).isEqualByComparingTo("500.0000");
    assertThat(l1.profitNote()).isEmpty();

    // 🔴 L2 挂了 2 个 SKU ⇒ 利润归不到唯一 listing ⇒ **空**（不是 0），并写明原因。
    assertThat(l2.totalSales()).isNull();
    assertThat(l2.netProfit()).isNull();
    assertThat(l2.profitNote()).contains("归不到唯一 listing");
    assertThat(l2.profitNote()).contains("不摊给任何一个");
  }

  // ==================================================================
  // 8. TACOS / 广告销售占比 / 广告后净利
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 TACOS / 广告销售占比 / 广告后净利 都拿利润侧做分母")
  void tacosAndAdSalesShareUseProfitSide() {
    seedStandard(actorA, a, "A");

    AdProductReportPage page = report(actorA, "LISTING", AdProductReportFixture.DAY_1,
        AdProductReportFixture.DAY_1, 0, 50);
    AdProductReportRow l1 = page.items().stream()
        .filter(r -> r.listingId().equals(lastData.l1().toString()))
        .findFirst()
        .orElseThrow();

    // 🔴 TACOS = 广告花费 / 总销售额 = 200 / 1200。
    assertThat(l1.tacos()).isEqualByComparingTo("0.166667");
    // 🔴 广告销售占比 = 广告销售额 / 总销售额 = 900 / 1200 = 0.75。
    assertThat(l1.adSalesShare()).isEqualByComparingTo("0.75");
    // 🔴 广告后净利 = 总净利 − 广告花费 = 500 − 200。
    assertThat(l1.adAfterNetProfit()).isEqualByComparingTo("300.0000");
    // 🔴 广告占比利润 = 广告花费 / 总净利 = 200 / 500。
    assertThat(l1.adProfitShare()).isEqualByComparingTo("0.4");
  }

  // ==================================================================
  // 9. 分母为 0 ⇒ 不可比（不是 0）
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 分母为 0 给空值（不可比），不是 0")
  void zeroDenominatorIsNotComparable() {
    String shop = seedShop(a, "SHOP-ZERO");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID listing = seedListing(a, shop, "ASIN-ZERO-1", "零销售商品");
    UUID campaign =
        seedCampaign(actorA, shop, profile, "EXT-ZERO-C1", "USD", "100", "5", "0", "10", "0");
    bindProducts(actorA, campaign, "GRP-ZERO-1", List.of(listing));

    AdProductReportPage page = report(actorA, "LISTING", AdProductReportFixture.DAY_1,
        AdProductReportFixture.DAY_1, 0, 50);

    assertThat(page.items()).hasSize(1);
    AdProductReportRow row = page.items().get(0);
    // 🔴 广告销售额为 0 ⇒ ACOS 不可比（null），**不是 0**。
    assertThat(row.acos()).isNull();
    assertThat(row.spend()).isEqualByComparingTo("10.0000");
    assertThat(row.sales()).isEqualByComparingTo("0.0000");
    // 🔴 ROAS 的分母是广告花费（这里不为 0）⇒ 可比；销售额为 0 不影响 ROAS。
    assertThat(row.roas()).isEqualByComparingTo("0");
  }

  // ==================================================================
  // 10. 真分页
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 真分页：分页真的分页，total 是全集")
  void paginationIsReal() {
    seedStandard(actorA, a, "A");

    AdProductReportPage first =
        report(actorA, "LISTING", AdProductReportFixture.DAY_1, AdProductReportFixture.DAY_1, 0, 1);
    assertThat(first.items()).hasSize(1);
    assertThat(first.total()).isEqualTo(2);
    // 🔴 汇总是**筛选后的全集**，不是当前页。
    assertThat(first.summaries().get(0).totalSpend()).isEqualByComparingTo("300.0000");

    AdProductReportPage second =
        report(actorA, "LISTING", AdProductReportFixture.DAY_1, AdProductReportFixture.DAY_1, 1, 1);
    assertThat(second.items()).hasSize(1);
    assertThat(second.items().get(0).dimensionKey()).isNotEqualTo(first.items().get(0).dimensionKey());
    assertThat(second.total()).isEqualTo(2);
  }

  // ==================================================================
  // 11. 参数校验
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 时间范围必填 + 跨度封顶 + 非法维度/排序一律 400")
  void parametersAreValidated() throws Exception {
    seedStandard(actorA, a, "A");

    // 🔴 不给时间范围 ⇒ 400（不给默认区间）。
    http.perform(get("/channel/ad-product-reports/rows").header("Authorization", bearerA))
        .andExpect(status().isBadRequest());
    // 🔴 跨度超过上限 ⇒ 400（不悄悄截断）。
    http.perform(
            get("/channel/ad-product-reports/rows")
                .header("Authorization", bearerA)
                .param("from", "2025-01-01")
                .param("to", "2026-12-31"))
        .andExpect(status().isBadRequest());
    // 🔴 非法维度 ⇒ 400。
    http.perform(
            get("/channel/ad-product-reports/rows")
                .header("Authorization", bearerA)
                .param("from", "2026-10-01")
                .param("to", "2026-10-02")
                .param("dimension", "CAMPAIGN"))
        .andExpect(status().isBadRequest());
    // 🔴 排序不在白名单 ⇒ 400。
    http.perform(
            get("/channel/ad-product-reports/rows")
                .header("Authorization", bearerA)
                .param("from", "2026-10-01")
                .param("to", "2026-10-02")
                .param("sort", "spend; drop table"))
        .andExpect(status().isBadRequest());
    // 🔴 没有会话 ⇒ 401。
    http.perform(
            get("/channel/ad-product-reports/rows")
                .param("from", "2026-10-01")
                .param("to", "2026-10-02"))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 12. 上游读不到 ⇒ 降级并说明原因
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 利润上游读不到：利润列取不到 + 如实说明原因（广告侧仍可用）")
  void profitUpstreamUnavailableDegrades() {
    seedStandard(actorA, a, "A");
    // 🔴 让 reporting 桩认不出这个会话 ⇒ 上游读不到。
    AdProductReportFixture.profitByBearer.remove(bearerOf(actorA));

    AdProductReportPage page = report(actorA, "LISTING", AdProductReportFixture.DAY_1,
        AdProductReportFixture.DAY_1, 0, 50);

    assertThat(page.profitAvailable()).isFalse();
    assertThat(page.profitUnavailableReason()).isNotBlank();
    // 🔴 利润列是**空**（不是 0）。
    assertThat(page.items().get(0).totalSales()).isNull();
    assertThat(page.items().get(0).tacos()).isNull();
    assertThat(page.items().get(0).adSalesShare()).isNull();
    // 🔴 广告侧仍然可用（这是「加了一段」，不是「全部依赖上游」）。
    assertThat(page.items().get(0).spend()).isEqualByComparingTo("200.0000");
    // 🔴 能力声明里利润侧的指标显式标 available=false。
    AdProductMetricDefinition tacos =
        page.metricDefinitions().stream()
            .filter(d -> d.metric().equals("tacos"))
            .findFirst()
            .orElseThrow();
    assertThat(tacos.available()).isFalse();
    assertThat(tacos.unavailableReason()).isNotBlank();
  }

  // ==================================================================
  // 13. 不同币种不合并
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 不同币种不合并：汇总按币种拆开")
  void differentCurrenciesAreNotMerged() {
    String shop = seedShop(a, "SHOP-MIX");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID l1 = seedListing(a, shop, "ASIN-MIX-1", "美元商品");
    UUID l2 = seedListing(a, shop, "ASIN-MIX-2", "欧元商品");
    UUID c1 = seedCampaign(actorA, shop, profile, "EXT-MIX-C1", "USD", "100", "5", "1", "10", "50");
    UUID c2 = seedCampaign(actorA, shop, profile, "EXT-MIX-C2", "EUR", "100", "5", "1", "20", "80");
    bindProducts(actorA, c1, "GRP-MIX-1", List.of(l1));
    bindProducts(actorA, c2, "GRP-MIX-2", List.of(l2));

    AdProductReportPage page = report(actorA, "LISTING", AdProductReportFixture.DAY_1,
        AdProductReportFixture.DAY_1, 0, 50);

    assertThat(page.summaries()).hasSize(2);
    assertThat(page.summaries().stream().map(AdProductReportSummary::currency).toList())
        .containsExactly("EUR", "USD");
    // 🔴 没有汇率源 ⇒ 绝不出现一个跨币种的合计（30）。
    for (AdProductReportSummary s : page.summaries()) {
      assertThat(s.totalSpend()).isLessThanOrEqualTo(new BigDecimal("20.0000"));
    }
  }

  // ==================================================================
  // 14. 导出留痕 + CSV 表头带口径
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 导出留痕 + CSV 表头带口径与归因规则")
  void exportLeavesTraceAndCsvHasCaliber() {
    seedStandard(actorA, a, "A");

    service.recordExport(
        service.normalize(
            AdProductReportFixture.DAY_1,
            AdProductReportFixture.DAY_1,
            "LISTING",
            "SPEND_DESC",
            null,
            null,
            null,
            null,
            0,
            50),
        actorA);

    AdProductReportExportPage exports = as(actorA, "channel.operation.read", () -> service.exports(0, 50));
    assertThat(exports.total()).isEqualTo(1);
    AdProductReportExportRow row = exports.items().get(0);
    assertThat(row.dimension()).isEqualTo("LISTING");
    assertThat(row.rowCount()).isEqualTo(2);
    // 🔴 留痕里带着**当时的口径与归因规则**（日后复盘不必猜）。
    assertThat(row.metricDefinitionNote()).contains("先汇总再相除");
    assertThat(row.attributionNote()).contains("等分");

    // ---- CSV 表头 ----
    List<String> chunks = new ArrayList<>();
    service.streamCsv(
        actorA,
        service.normalize(
            AdProductReportFixture.DAY_1,
            AdProductReportFixture.DAY_1,
            "LISTING",
            "SPEND_DESC",
            null,
            null,
            null,
            null,
            0,
            50),
        chunks::add,
        bearerOf(actorA));
    String csv = String.join("", chunks);
    assertThat(csv).contains("ACOS(Σ花费/Σ广告销售额)");
    assertThat(csv).contains("TACOS(Σ广告花费/总销售额)");
    assertThat(csv).contains("广告销售占比(广告销售额/总销售额)");
    assertThat(csv).contains("归因方式");
    // 🔴 不可比的比率写「不可比」，不是 0。
    assertThat(csv).contains("不可比");
    assertThat(csv.split("\n").length).isGreaterThan(2);
  }

  // ==================================================================
  // 15. timestamptz 往返：断言写回的值
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 timestamptz 往返：写回值逐字相等（不偏 −28800s）")
  void dataAsOfRoundTripPreservesOffset() {
    seedStandard(actorA, a, "A");
    OffsetDateTime written = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");

    AdProductReportFixture.execAsTenant(
        a,
        "update ad_campaign_sync_run set data_as_of = ?::timestamptz where tenant_id = '"
            + a
            + "'",
        written);

    String readBack =
        AdProductReportFixture.execOneAsTenant(
            a,
            "select to_char(data_as_of at time zone 'Asia/Shanghai', 'YYYY-MM-DD\"T\"HH24:MI:SS')"
                + " from ad_campaign_sync_run where tenant_id = '"
                + a
                + "' limit 1");
    assertThat(readBack).isEqualTo("2026-10-01T09:00:00");

    // 🔴 报表里的「数据截至 X 时间」也要是同一个时刻。
    AdProductReportPage page = report(actorA, "LISTING", AdProductReportFixture.DAY_1,
        AdProductReportFixture.DAY_1, 0, 50);
    assertThat(page.dataAsOf()).isNotNull();
    assertThat(page.dataAsOf().toInstant()).isEqualTo(written.toInstant());
  }

  // ==================================================================
  // 16. HTTP 端点
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 HTTP：能力声明 / 报表 / 导出留痕三个端点都通")
  void httpEndpointsRespond() throws Exception {
    seedStandard(actorA, a, "A");

    http.perform(get("/channel/ad-product-reports/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions").isArray())
        .andExpect(jsonPath("$.attributionNote").isNotEmpty());

    http.perform(get("/channel/ad-product-reports/options").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions").isArray());

    http.perform(
            get("/channel/ad-product-reports/rows")
                .header("Authorization", bearerA)
                .param("from", "2026-10-01")
                .param("to", "2026-10-02")
                .param("dimension", "SKU"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.summaries").isArray())
        .andExpect(jsonPath("$.metricDefinitions").isArray());
  }
}
