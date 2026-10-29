package com.starcon.channel;

import static com.starcon.channel.AdCampaignModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 14.3.1.3 商品维度报表的**多租户隔离**测试。
 *
 * <p>AGENTS.md 的硬性要求：集成测试**必须**证明这三条，缺一不可：
 *
 * <ol>
 *   <li>两租户互不可见
 *   <li>伪造 tenant header 无效
 *   <li>连接池归还后不串租户
 * </ol>
 *
 * <p>🔴 本模块读跨服务（13.4.1.2 reporting、03 catalog）⇒ 租户隔离是<b>两道</b>：
 * 本服务靠 RLS，上游靠「转发调用方自己的 bearer」。桩按 bearer 分租户 ——
 * 伪造 {@code X-Tenant-Id} 头在两边都改不动。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdProductReportTenancyIntegrationTest {

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  @BeforeAll
  static void boot() {
    AdProductReportFixture.start();
  }

  @AfterAll
  static void shutdown() {
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
    r.add(
        "IDENTITY_URL",
        () -> "http://localhost:" + AdProductReportFixture.identity.getAddress().getPort());
    r.add(
        "REPORTING_URL",
        () -> "http://localhost:" + AdProductReportFixture.reporting.getAddress().getPort());
    r.add(
        "CATALOG_URL",
        () -> "http://localhost:" + AdProductReportFixture.catalog.getAddress().getPort());
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

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 播种（走真实写入口）
  // ==================================================================

  void seedShop(UUID tenant, String code) {
    AdProductReportFixture.execAsTenant(
        tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenant
            + "','"
            + UUID.randomUUID()
            + "','"
            + AdProductReportFixture.AMAZON
            + "','"
            + code
            + "','店铺 "
            + code
            + "') on conflict (tenant_id,code) do nothing");
  }

  UUID seedListing(UUID tenant, String shopCode, String externalItemId) {
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
            + "','商品 "
            + externalItemId
            + "','ON_SALE')");
    return id;
  }

  /** 播一个只属于某个租户的投放商品与活动（走真实写入口）。 */
  void seedOne(UUID tenant, String bearer, String suffix, String spend) {
    seedShop(tenant, "SHOP-" + suffix);
    String shopCode = "SHOP-" + suffix;
    UUID listingId = seedListing(tenant, shopCode, "ASIN-" + suffix);

    UUID profile =
        as(
                actorOf(tenant),
                "channel.operation.create",
                () ->
                    adTypes.saveProfile(
                        new AdTypeDiffModels.SaveProfileRequest(
                            null,
                            AdProductReportFixture.AMAZON,
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
        actorOf(tenant),
        "channel.operation.create",
        () ->
            campaigns.importCampaigns(
                new ImportRequest(
                    AdProductReportFixture.AMAZON,
                    shopCode,
                    new ImportSyncRun(
                        "SUCCESS",
                        AdProductReportFixture.DATA_AS_OF,
                        AdProductReportFixture.STARTED_AT,
                        AdProductReportFixture.FINISHED_AT,
                        "",
                        "batch-" + suffix),
                    List.of(
                        new ImportCampaign(
                            "EXT-" + suffix,
                            "活动 " + suffix,
                            profile,
                            "ENABLED",
                            "USD",
                            new BigDecimal("100.0000"),
                            null,
                            null,
                            List.of(
                                new ImportDailyMetric(
                                    AdProductReportFixture.DAY_1,
                                    new BigDecimal("100"),
                                    new BigDecimal("10"),
                                    new BigDecimal("1"),
                                    new BigDecimal(spend),
                                    new BigDecimal("50"))),
                            "测试登记")))));

    UUID campaignId =
        UUID.fromString(
            AdProductReportFixture.execOneAsTenant(
                tenant,
                "select id from ad_campaign where tenant_id = '"
                    + tenant
                    + "' and external_campaign_id = 'EXT-"
                    + suffix
                    + "'"));

    as(
        actorOf(tenant),
        "channel.operation.create",
        () ->
            details.importStructure(
                campaignId.toString(),
                new AdCampaignDetailModels.AdCampaignStructureImportRequest(
                    AdProductReportFixture.STRUCTURE_AS_OF,
                    List.of(
                        new AdCampaignDetailModels.AdCampaignGroupImport(
                            "GRP-" + suffix,
                            "组 " + suffix,
                            "ENABLED",
                            new BigDecimal("1.5000"),
                            "USD",
                            new BigDecimal("50.0000"),
                            "AUTO",
                            List.of(
                                new AdCampaignDetailModels.AdCampaignProductImport(
                                    listingId, null, null)),
                            List.of())),
                    List.of())));

    AdProductReportFixture.skusByBearer.put(bearer, List.of());
    AdProductReportFixture.profitByBearer.put(bearer, List.of());
  }

  AccessService.Access actorOf(UUID tenant) {
    for (var e : AdProductReportFixture.actors.entrySet()) {
      if (e.getValue().tenantId().equals(tenant)) return e.getValue();
    }
    throw new IllegalStateException("找不到该租户的会话");
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 多租户断言一：两租户互不可见")
  void twoTenantsCannotSeeEachOther() {
    seedOne(a, bearerA, "TA", "11.0000");
    seedOne(b, bearerB, "TB", "22.0000");

    var pageA = report(actorA);
    var pageB = report(actorB);

    // 🔴 各自只看得到自己的商品。
    assertThat(pageA.items()).hasSize(1);
    assertThat(pageB.items()).hasSize(1);
    assertThat(pageA.items().get(0).spend()).isEqualByComparingTo("11.0000");
    assertThat(pageB.items().get(0).spend()).isEqualByComparingTo("22.0000");
    assertThat(pageA.items().get(0).dimensionKey()).isNotEqualTo(pageB.items().get(0).dimensionKey());

    // 🔴 直查数据库也分得开（RLS 层）。
    List<String> listingsA =
        AdProductReportFixture.queryStringsAsRlsTenant(
            a, "select id::text from listing order by id");
    List<String> listingsB =
        AdProductReportFixture.queryStringsAsRlsTenant(
            b, "select id::text from listing order by id");
    assertThat(listingsA).hasSize(1);
    assertThat(listingsB).hasSize(1);
    assertThat(listingsA).doesNotContainAnyElementsOf(listingsB);

    // 🔴 本模块读的表都开了 FORCE RLS。
    for (String table :
        List.of(
            "ad_campaign_daily_metric",
            "ad_campaign",
            "ad_campaign_group_product",
            "listing",
            "listing_variant",
            "ad_product_report_export")) {
      boolean forced =
          AdProductReportFixture.queryStringsAsRlsTenant(
                  a, "select relforcerowsecurity::text from pg_class where relname = '" + table + "'")
              .stream()
              .findFirst()
              .map(Boolean::parseBoolean)
              .orElse(false);
      assertThat(forced).as("表 %s 必须 FORCE RLS", table).isTrue();
    }
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 多租户断言二：伪造 X-Tenant-Id 头无效")
  void forgedTenantHeaderHasNoEffect() throws Exception {
    seedOne(a, bearerA, "TA", "11.0000");
    seedOne(b, bearerB, "TB", "22.0000");

    // 🔴 用 A 的会话 + B 的租户头 ⇒ 看到的仍然是 A 的数据（读侧）。
    http.perform(
            get("/channel/ad-product-reports/rows")
                .header("Authorization", bearerA)
                .header("X-Tenant-Id", b.toString())
                .param("from", "2026-10-01")
                .param("to", "2026-10-02"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].spend").value(11.0));

    // 🔴 伪造头也不能把写入落到别人名下：导出留痕仍然记在 A 自己名下。
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
    assertThat(as(actorA, "channel.operation.read", () -> service.exports(0, 50)).total())
        .isEqualTo(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.exports(0, 50)).total())
        .isEqualTo(0);
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("14.3.1.3 多租户断言三：连接池归还后不串租户")
  void connectionPoolDoesNotLeakTenant() {
    seedOne(a, bearerA, "TA", "11.0000");
    seedOne(b, bearerB, "TB", "22.0000");

    // 🔴 单连接池下 A → B → A 三轮。
    for (int round = 0; round < 3; round++) {
      var pageA = report(actorA);
      assertThat(pageA.items()).hasSize(1);
      assertThat(pageA.items().get(0).spend()).isEqualByComparingTo("11.0000");

      var pageB = report(actorB);
      assertThat(pageB.items()).hasSize(1);
      assertThat(pageB.items().get(0).spend()).isEqualByComparingTo("22.0000");

      var againA = report(actorA);
      assertThat(againA.items()).hasSize(1);
      assertThat(againA.items().get(0).spend()).isEqualByComparingTo("11.0000");
    }
  }

  // ==================================================================
  // 辅助
  // ==================================================================

  AdProductReportModels.AdProductReportPage report(AccessService.Access who) {
    return service.report(
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
        who,
        bearerOf(who));
  }

  static String bearerOf(AccessService.Access who) {
    for (var e : AdProductReportFixture.actors.entrySet()) {
      if (e.getValue().tenantId().equals(who.tenantId())) return e.getKey();
    }
    throw new IllegalStateException("找不到该成员的会话");
  }
}
