package com.starcon.channel;

import static com.starcon.channel.AdAttributionModels.*;
import static com.starcon.channel.AdCampaignModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

/**
 * 14.3.2.1 归因分析的**多租户隔离**测试。
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
 *
 * <p>🔴 本模块的五张表都在 RLS 后面 ⇒ 隔离断言逐表核对
 * {@code relforcerowsecurity = true}；用**应用角色**（channel_app，nobypassrls）查 ——
 * 容器超级用户是 BYPASSRLS 的，用它查证明不了隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdAttributionTenancyIntegrationTest {

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  @BeforeAll
  static void boot() {
    AdAttributionFixture.start();
  }

  @AfterAll
  static void shutdown() {
    AdAttributionFixture.stop();
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants =
        List.of(AdAttributionFixture.grant("ALL", AdAttributionFixture.allPermissions));
    AdAttributionFixture.actors.put(
        bearerA, AdAttributionFixture.actor(UUID.randomUUID(), a, grants));
    AdAttributionFixture.actors.put(
        bearerB, AdAttributionFixture.actor(UUID.randomUUID(), b, grants));
    actorA = AdAttributionFixture.actors.get(bearerA);
    actorB = AdAttributionFixture.actors.get(bearerB);
    AdAttributionFixture.seedGlobalCatalog();
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
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
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
  // 播种（走真实写入口）
  // ==================================================================

  static OffsetDateTime dayStart() {
    return AdAttributionFixture.DAY_1.atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime();
  }

  static OffsetDateTime orderAt() {
    return dayStart().plusHours(10);
  }

  /** 播一个只属于某个租户的店铺 + 活动 + 归因数据 + 归因结果。 */
  void seedOne(UUID tenant, String bearer, String suffix, String spend, String sales) {
    AdAttributionFixture.execAsTenant(
        tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenant
            + "','"
            + UUID.randomUUID()
            + "','"
            + AdAttributionFixture.AMAZON
            + "','SHOP-"
            + suffix
            + "','店铺 "
            + suffix
            + "') on conflict (tenant_id,code) do nothing");

    AccessService.Access who = actorOf(tenant);
    UUID profile =
        as(
                who,
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
        who,
        "channel.operation.create",
        () ->
            campaigns.importCampaigns(
                new ImportRequest(
                    AdAttributionFixture.AMAZON,
                    "SHOP-" + suffix,
                    new ImportSyncRun(
                        "SUCCESS",
                        AdAttributionFixture.DATA_AS_OF,
                        AdAttributionFixture.STARTED_AT,
                        AdAttributionFixture.FINISHED_AT,
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
                                    AdAttributionFixture.DAY_1,
                                    new BigDecimal("1000"),
                                    new BigDecimal("100"),
                                    new BigDecimal("10"),
                                    new BigDecimal("350.0000"),
                                    new BigDecimal("200.0000"))),
                            "测试登记")))));

    UUID campaignId =
        UUID.fromString(
            AdAttributionFixture.execOneAsTenant(
                tenant,
                "select id from ad_campaign where tenant_id = '"
                    + tenant
                    + "' and external_campaign_id = 'EXT-"
                    + suffix
                    + "'"));

    UUID skuId = UUID.randomUUID();
    AdAttributionFixture.skusByBearer.put(bearer, List.of());
    AdAttributionFixture.profitByBearer.put(bearer, List.of());

    var rule =
        as(
            who,
            "channel.operation.read",
            () ->
                service.saveRule(
                    new SaveAdAttributionRuleRequest(
                        null, "RULE-" + suffix, "LAST_CLICK", 30, "测试规则", true)));

    as(
        who,
        "channel.operation.read",
        () ->
            service.importAttribution(
                new AdAttributionImportRequest(
                    AdAttributionFixture.DATA_AS_OF,
                    List.of(
                        new AdAttributionOrderImport(
                            campaignId,
                            "EXT-" + suffix,
                            "SHOP-" + suffix,
                            "AMAZON",
                            "ORD-" + suffix,
                            orderAt(),
                            skuId,
                            "USD",
                            new BigDecimal(spend),
                            new BigDecimal(sales),
                            BigDecimal.ONE,
                            BigDecimal.ONE,
                            List.of(
                                new AdAttributionClickImport(
                                    "C-" + suffix, orderAt().minusHours(1), true)))))));

    as(
        who,
        "channel.operation.read",
        () ->
            service.run(
                new AdAttributionRunRequest(
                    rule.id(), AdAttributionFixture.DAY_1, AdAttributionFixture.DAY_1, false)));
  }

  AccessService.Access actorOf(UUID tenant) {
    for (var e : AdAttributionFixture.actors.entrySet()) {
      if (e.getValue().tenantId().equals(tenant)) return e.getValue();
    }
    throw new IllegalStateException("找不到该租户的会话");
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("14.3.2.1 多租户断言一：两租户互不可见")
  void twoTenantsCannotSeeEachOther() {
    seedOne(a, bearerA, "TA", "11.0000", "21.0000");
    seedOne(b, bearerB, "TB", "22.0000", "42.0000");

    var pageA = records(actorA, bearerA);
    var pageB = records(actorB, bearerB);

    // 🔴 各自只看得到自己的归因结果。
    assertThat(pageA.items()).hasSize(1);
    assertThat(pageB.items()).hasSize(1);
    assertThat(pageA.items().get(0).attributedSpend()).isEqualByComparingTo("11.0000");
    assertThat(pageB.items().get(0).attributedSpend()).isEqualByComparingTo("22.0000");
    assertThat(pageA.items().get(0).externalOrderNo()).isNotEqualTo(pageB.items().get(0).externalOrderNo());

    // 🔴 直查数据库也分得开（**RLS 层**，用应用角色查 —— 超级用户是 BYPASSRLS，证明不了）。
    List<String> factsA =
        AdAttributionFixture.queryStringsAsRlsTenant(
            a, "select id::text from ad_attribution_fact order by id");
    List<String> factsB =
        AdAttributionFixture.queryStringsAsRlsTenant(
            b, "select id::text from ad_attribution_fact order by id");
    assertThat(factsA).hasSize(1);
    assertThat(factsB).hasSize(1);
    assertThat(factsA).doesNotContainAnyElementsOf(factsB);

    // 🔴 归因规则也分得开（A 的租户看不到 B 的规则）。
    List<String> rulesA =
        AdAttributionFixture.queryStringsAsRlsTenant(
            a, "select rule_code from ad_attribution_rule order by rule_code");
    List<String> rulesB =
        AdAttributionFixture.queryStringsAsRlsTenant(
            b, "select rule_code from ad_attribution_rule order by rule_code");
    assertThat(rulesA).containsExactly("RULE-TA");
    assertThat(rulesB).containsExactly("RULE-TB");

    // 🔴 本模块的五张表都开了 FORCE RLS。
    for (String table :
        List.of(
            "ad_attribution_rule",
            "ad_attribution_order",
            "ad_attribution_click",
            "ad_attribution_fact",
            "ad_attribution_export")) {
      boolean forced =
          AdAttributionFixture.queryStringsAsRlsTenant(
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
  @DisplayName("14.3.2.1 多租户断言二：伪造 X-Tenant-Id 头无效")
  void forgedTenantHeaderHasNoEffect() throws Exception {
    seedOne(a, bearerA, "TA", "11.0000", "21.0000");
    seedOne(b, bearerB, "TB", "22.0000", "42.0000");

    // 🔴 用 A 的会话 + B 的租户头 ⇒ 看到的仍然是 A 的数据（读侧）。
    http.perform(
            get("/channel/ad-attributions/records")
                .header("Authorization", bearerA)
                .header("X-Tenant-Id", b.toString())
                .param("from", AdAttributionFixture.DAY_1.toString())
                .param("to", AdAttributionFixture.DAY_1.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].attributedSpend").value(11.0));

    // 🔴 伪造头也不能把写入落到别人名下：导出留痕仍然记在 A 自己名下。
    var trace =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.recordExport(
                    service.normalizeReportQuery(
                        null,
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
                    actorA));
    assertThat(as(actorA, "channel.operation.read", () -> service.exports(0, 50)).total())
        .isEqualTo(1);
    assertThat(as(actorB, "channel.operation.read", () -> service.exports(0, 50)).total())
        .isEqualTo(0);
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("14.3.2.1 多租户断言三：连接池归还后不串租户")
  void connectionPoolDoesNotLeakTenant() {
    seedOne(a, bearerA, "TA", "11.0000", "21.0000");
    seedOne(b, bearerB, "TB", "22.0000", "42.0000");

    // 🔴 单连接池下 A → B → A 三轮。
    for (int round = 0; round < 3; round++) {
      var pageA = records(actorA, bearerA);
      assertThat(pageA.items()).hasSize(1);
      assertThat(pageA.items().get(0).attributedSpend()).isEqualByComparingTo("11.0000");

      var pageB = records(actorB, bearerB);
      assertThat(pageB.items()).hasSize(1);
      assertThat(pageB.items().get(0).attributedSpend()).isEqualByComparingTo("22.0000");

      var againA = records(actorA, bearerA);
      assertThat(againA.items()).hasSize(1);
      assertThat(againA.items().get(0).attributedSpend()).isEqualByComparingTo("11.0000");
    }
  }

  // ==================================================================
  // 辅助
  // ==================================================================

  AdAttributionRecordPage records(AccessService.Access who, String bearer) {
    return as(
        who,
        "channel.operation.read",
        () ->
            service.records(
                service.normalizeRecordQuery(
                    null,
                    null,
                    AdAttributionFixture.DAY_1,
                    AdAttributionFixture.DAY_1,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "ORDER_AT_DESC",
                    0,
                    50)));
  }
}
