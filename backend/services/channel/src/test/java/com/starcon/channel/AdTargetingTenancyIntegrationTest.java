package com.starcon.channel;

import static com.starcon.channel.AdCampaignModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 14.1.3.2 投放定位 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（定位设置 / 效果指标 / 操作留痕 / 对比表 / 建议 / 时段画像 都算）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdTargetingTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  UUID a, b;
  String bearerA, bearerB;

  static final OffsetDateTime DATA_AS_OF = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
  static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
  static final OffsetDateTime FINISHED_AT = OffsetDateTime.parse("2026-10-01T09:05:00+08:00");
  static final LocalDate DAY_1 = LocalDate.of(2026, 10, 1);
  static final String FROM = "2026-10-01";
  static final String TO = "2026-10-31";

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer att-t-a-" + a;
    bearerB = "Bearer att-t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
        + " on conflict (id) do nothing");
    exec("insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
        + " values ('" + UUID.randomUUID() + "','" + AMAZON + "','AD_TYPE','sponsored_products','AUTO_DISCOVERY')"
        + " on conflict (platform_id,enum_kind,external_value) do nothing");
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired AdTargetingService service;
  @Autowired AdCampaignService campaigns;
  @Autowired AdTypeDiffService adTypes;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String bearer, String permission, java.util.function.Supplier<T> work) {
    return access.run(actors.get(bearer), permission, work);
  }

  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static void execAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 以<b>应用角色 channel_app</b>（非超级用户）直查一行计数。
   *
   * <p>🔴 必须用 channel_app 连：Testcontainers 默认的 {@code pg.getUsername()} 是超级用户，
   * 而超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」。
   */
  static long countAsTenant(UUID tenant, String sql) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以管理员连接读一行标量（只用于读 pg_class 这类不受 RLS 约束的事实）。 */
  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  void seedShop(UUID tenant, String code) {
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + UUID.randomUUID()
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
  }

  /** 一个租户里的活动 + 定位设置 + 一条效果数据。 */
  UUID seedTargeting(String bearer, UUID tenant, String suffix, String dimensionValue) {
    seedShop(tenant, "ATTT-" + suffix);
    UUID profile =
        as(bearer, "channel.operation.create",
            () -> adTypes.saveProfile(new AdTypeDiffModels.SaveProfileRequest(
                null, AMAZON, "sponsored_products", "AUTO_DISCOVERY", "展示名", "CPC", "SALES",
                true, true, true, true, false, "PLATFORM_DEFAULT", "测试登记")))
            .id();
    as(bearer, "channel.operation.create",
        () -> campaigns.importCampaigns(new ImportRequest(
            AMAZON, "ATTT-" + suffix,
            new ImportSyncRun("SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-" + suffix),
            List.of(
                new ImportCampaign(
                    "EXT-" + suffix, "租户隔离活动 " + suffix, profile, "ENABLED", "USD",
                    new BigDecimal("100.0000"), null, null, List.of(), "测试登记")))));
    UUID campaignId =
        UUID.fromString(
            scalar(
                "select id from ad_campaign where tenant_id = '" + tenant + "' and external_campaign_id = 'EXT-"
                    + suffix + "'"));
    var setting =
        as(bearer, "channel.operation.create",
            () -> service.create(new AdTargetingModels.AdTargetingCreateRequest(
                campaignId, null, "GEO", dimensionValue, "geo", dimensionValue, null, null, null, "隔离测试")));
    as(bearer, "channel.operation.create",
        () -> service.ingestMetrics(
            new AdTargetingModels.AdTargetingMetricBatchRequest(
                List.of(
                    new AdTargetingModels.AdTargetingMetricEntry(
                        setting.id(), DAY_1, 1000L, 100L, 10,
                        new BigDecimal("100.0000"), new BigDecimal("500.0000"), "USD", null, "")),
                "batch-" + suffix, "PLATFORM", "src-" + suffix, "隔离测试")));
    return setting.id();
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("14.1.3.2 断言一：两租户互不可见（设置 / 指标 / 留痕 / 对比表 / 建议 / 时段画像）")
  void tenantsAreIsolated() {
    UUID settingA = seedTargeting(bearerA, a, "A", "US");
    UUID settingB = seedTargeting(bearerB, b, "B", "CA");

    // ---- 列表：A 只看到自己的 ----
    var pageA = as(bearerA, "channel.operation.read", () -> service.settings(null, null, null, null, null, null, null, 0, 50));
    assertThat(pageA.items()).hasSize(1);
    assertThat(pageA.items().get(0).id()).isEqualTo(settingA);
    assertThat(pageA.items().get(0).dimensionValue()).isEqualTo("US");

    var pageB = as(bearerB, "channel.operation.read", () -> service.settings(null, null, null, null, null, null, null, 0, 50));
    assertThat(pageB.items()).hasSize(1);
    assertThat(pageB.items().get(0).id()).isEqualTo(settingB);

    // ---- 详情：B 的设置在 A 的上下文里是 404 ----
    var thrown = catchThrowable(() -> as(bearerA, "channel.operation.read", () -> service.detail(settingB.toString())));
    assertThat(thrown).isNotNull();

    // ---- 对比表：A 只看到自己的花费 ----
    var effectsA = as(bearerA, "channel.operation.read",
        () -> service.effects(null, null, null, null, null, FROM, TO, "spendDesc", 0, 50));
    assertThat(effectsA.total()).isEqualTo(1);
    assertThat(effectsA.items().get(0).dimensionValue()).isEqualTo("US");
    assertThat(effectsA.items().get(0).spend()).isEqualByComparingTo("100.0000");

    var effectsB = as(bearerB, "channel.operation.read",
        () -> service.effects(null, null, null, null, null, FROM, TO, "spendDesc", 0, 50));
    assertThat(effectsB.total()).isEqualTo(1);
    assertThat(effectsB.items().get(0).dimensionValue()).isEqualTo("CA");

    // ---- 建议：A 只看到自己的 ----
    var suggestionsA = as(bearerA, "channel.operation.read",
        () -> service.suggestions(null, null, null, FROM, TO));
    assertThat(suggestionsA.items()).allMatch(s -> s.dimensionValue().equals("US"));

    // ---- 时段画像：A 看不到 B 的 ----
    var profileA = as(bearerA, "channel.operation.read", () -> service.daypartProfile(null, null, FROM, TO));
    assertThat(profileA.items()).isEmpty();

    // ---- 库里：A 的上下文读不到 B 的行 ----
    assertThat(countAsTenant(a,
            "select count(*) from ad_targeting_setting where id='" + settingB + "'"))
        .isZero();
    assertThat(countAsTenant(a,
            "select count(*) from ad_targeting_metric where setting_id='" + settingB + "'"))
        .isZero();
    assertThat(countAsTenant(b,
            "select count(*) from ad_targeting_setting where id='" + settingA + "'"))
        .isZero();
    assertThat(countAsTenant(b,
            "select count(*) from ad_targeting_metric where setting_id='" + settingA + "'"))
        .isZero();

    // ---- 三张表都是 FORCE RLS ----
    for (String table : List.of("ad_targeting_setting", "ad_targeting_metric", "ad_targeting_operation_log")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
    }

    // ---- 同一机制在本租户自己的数据上必须看得见行 ----
    assertThat(countAsTenant(a,
            "select count(*) from ad_targeting_setting where id='" + settingA + "'"))
        .as("A 的上下文必须能直查 A 自己的定位设置（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a,
            "select count(*) from ad_targeting_metric where setting_id='" + settingA + "'"))
        .isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("14.1.3.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID settingA = seedTargeting(bearerA, a, "FA", "US");
    UUID settingB = seedTargeting(bearerB, b, "FB", "CA");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的定位设置
    http.perform(get("/channel/ad-targetings/" + settingB)
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/ad-targetings")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(settingA.toString()));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的
    http.perform(get("/channel/ad-targetings")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(settingA.toString()));

    // A 用伪造的 B 头去建定位设置 ⇒ 仍然落在 A 的租户里
    UUID campaignA =
        UUID.fromString(
            scalar("select id from ad_campaign where tenant_id = '" + a + "' and external_campaign_id = 'EXT-FA'"));
    http.perform(post("/channel/ad-targetings")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString())
            .contentType("application/json")
            .content(
                new ObjectMapper()
                    .writeValueAsString(
                        new AdTargetingModels.AdTargetingCreateRequest(
                            campaignA, null, "GEO", "DE", "geo", "DE", null, null, null, "伪造头测试"))))
        .andExpect(status().isOk());

    assertThat(countAsTenant(a,
            "select count(*) from ad_targeting_setting where dimension_value='DE'"))
        .as("伪造的头不能把数据写进别的租户").isEqualTo(1);
    assertThat(countAsTenant(b,
            "select count(*) from ad_targeting_setting where dimension_value='DE'"))
        .as("B 的租户里不能出现 A 登记的定位设置").isZero();

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/ad-targetings/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("14.1.3.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID settingA = seedTargeting(bearerA, a, "PA", "US");
    UUID settingB = seedTargeting(bearerB, b, "PB", "CA");

    for (int i = 0; i < 3; i++) {
      var pageA = as(bearerA, "channel.operation.read",
          () -> service.settings(null, null, null, null, null, null, null, 0, 50));
      assertThat(pageA.items()).hasSize(1);
      assertThat(pageA.items().get(0).id()).isEqualTo(settingA);
      assertThat(pageA.items().get(0).dimensionValue()).isEqualTo("US");

      var pageB = as(bearerB, "channel.operation.read",
          () -> service.settings(null, null, null, null, null, null, null, 0, 50));
      assertThat(pageB.items()).hasSize(1);
      assertThat(pageB.items().get(0).id()).isEqualTo(settingB);
      assertThat(pageB.items().get(0).dimensionValue()).isEqualTo("CA");

      // 🔴 第二条查询形状：效果对比表
      var effectsA = as(bearerA, "channel.operation.read",
          () -> service.effects(null, null, null, null, null, FROM, TO, "spendDesc", 0, 50));
      assertThat(effectsA.total()).isEqualTo(1);
      assertThat(effectsA.items().get(0).spend()).isEqualByComparingTo("100.0000");
      var effectsB = as(bearerB, "channel.operation.read",
          () -> service.effects(null, null, null, null, null, FROM, TO, "spendDesc", 0, 50));
      assertThat(effectsB.total()).isEqualTo(1);

      // 🔴 第三条查询形状：详情
      var detailA = as(bearerA, "channel.operation.read", () -> service.detail(settingA.toString()));
      assertThat(detailA.setting().dimensionValue()).isEqualTo("US");
      var detailB = as(bearerB, "channel.operation.read", () -> service.detail(settingB.toString()));
      assertThat(detailB.setting().dimensionValue()).isEqualTo("CA");

      // 🔴 第四条查询形状：建议
      var suggestionsA = as(bearerA, "channel.operation.read",
          () -> service.suggestions(null, null, null, FROM, TO));
      assertThat(suggestionsA.items()).allMatch(s -> s.dimensionValue().equals("US"));
    }
  }
}
