package com.starcon.channel;

import static com.starcon.channel.AdDaypartModels.*;
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
 * 14.2.1.2 分时策略 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（策略 / 曲线 / 基准 / 推荐批次 / 推荐明细 / 任务 / 任务明细 都算）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 *
 * <p>🔴 本模块的表<b>全部</b>开了 {@code FORCE ROW LEVEL SECURITY} + {@code data_scope}
 * （按店铺收敛）⇒ 租户隔离是**两道**：先是 {@code isolation}（tenant_id），
 * 再是 {@code data_scope}（店铺负责人 / 部门）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdDaypartTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  UUID a, b;
  String bearerA, bearerB;

  static final OffsetDateTime DATA_AS_OF = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
  static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
  static final OffsetDateTime FINISHED_AT = OffsetDateTime.parse("2026-10-01T09:05:00+08:00");

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID AMAZON_REAL = UUID.fromString("11111111-1111-1111-1111-111111111101");
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

  /**
   * 🔴 **先停外部服务、再停容器**（本仓既有约定，见 BackfillIntegrationTest 等）。
   *
   * <p>不回收的话，一个测试类就会在机器上留下一个 postgres:16-alpine 容器；
   * 同一个服务堆到上百个容器会把机器压进 swap ⇒ **断言假红**（负载型假红，不是本模块的错）。
   */
  @AfterAll
  static void stopEverything() {
    if (identity != null) identity.stop(0);
    pg.stop();
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer adt-a-" + a;
    bearerB = "Bearer adt-b-" + b;
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
    r.add("REPORTING_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired AdDaypartService service;
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

  /** 播种一个店铺 + 站点（时区定得了）+ 一个活动。 */
  void seedShopAndCampaign(String bearer, UUID tenant, String code, String budget) {
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + UUID.randomUUID()
            + "','" + AMAZON + "','" + code + "','隔离测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    String shopId =
        execOneAsTenant(tenant, "select id::text from shop where tenant_id = '" + tenant + "' and code = '" + code + "'");
    String marketplaceId =
        execOneAsTenant(
            tenant,
            "select id::text from marketplace where platform_id = '"
                + AMAZON_REAL
                + "' and code = 'US'");
    execAsTenant(tenant,
        "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status)"
            + " values ('" + tenant + "','" + shopId + "','" + marketplaceId + "','ACTIVE')"
            + " on conflict (tenant_id,shop_id,marketplace_id) do nothing");

    UUID profile =
        as(bearer, "channel.operation.create",
            () -> adTypes.saveProfile(new AdTypeDiffModels.SaveProfileRequest(
                    null, AMAZON, "sponsored_products", "AUTO_DISCOVERY", "展示名", "CPC", "SALES",
                    true, true, true, true, false, "PLATFORM_DEFAULT", "隔离测试"))
                .id());
    as(bearer, "channel.operation.create",
        () -> campaigns.importCampaigns(new AdCampaignModels.ImportRequest(
            AMAZON,
            code,
            new AdCampaignModels.ImportSyncRun(
                "SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-" + code),
            List.of(
                new AdCampaignModels.ImportCampaign(
                    "EXT-" + code,
                    "隔离测试活动 " + code,
                    profile,
                    "ENABLED",
                    "USD",
                    new BigDecimal(budget),
                    null,
                    null,
                    List.of(),
                    "隔离测试")))));
  }

  static String execOneAsTenant(UUID tenant, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 建一条策略 + 一条会动的曲线（**激活**了才有任务可跑）。 */
  AdDaypartStrategyView seedStrategy(String bearer, UUID tenant, String shopCode, String code) {
    var strategy =
        as(bearer, "channel.operation.create",
            () -> service.createStrategy(
                new AdDaypartStrategyRequest(
                    code, "策略 " + code, shopCode, "ALL_CAMPAIGNS", null, "USD",
                    new BigDecimal("1000.0000"), new BigDecimal("0.500000"),
                    new BigDecimal("5000.0000"), 50, 24,
                    null, null, null, null,
                    Boolean.FALSE, Boolean.FALSE, 60,
                    null, null, null, null, null, null, null, null, null, "隔离测试")));
    List<AdDaypartHourInput> hours = new java.util.ArrayList<>();
    for (int hour = 0; hour < 24; hour++) {
      hours.add(new AdDaypartHourInput(hour, "BOOST", new BigDecimal("1.500000"), new BigDecimal("1.500000"), "隔离测试"));
    }
    as(bearer, "channel.operation.create",
        () -> service.replaceCurve(strategy.id(), new AdDaypartCurveRequest(hours)));
    as(bearer, "channel.operation.create",
        () -> service.changeStatus(strategy.id(), new AdDaypartStatusRequest("ACTIVE", "激活")));
    return strategy;
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("14.2.1.2 断言一：两租户互不可见（策略 / 曲线 / 基准 / 任务 / 明细）")
  void tenantsAreIsolated() {
    seedShopAndCampaign(bearerA, a, "ADT-A", "100.0000");
    seedShopAndCampaign(bearerB, b, "ADT-B", "200.0000");

    var strategyA = seedStrategy(bearerA, a, "ADT-A", "ADP-ISO-A");
    var strategyB = seedStrategy(bearerB, b, "ADT-B", "ADP-ISO-B");

    // ---- 列表：A 只看到自己的 ----
    var pageA = as(bearerA, "channel.operation.read", () -> service.strategies(null, null, null, 0, 50));
    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageA.items().get(0).id()).isEqualTo(strategyA.id());
    assertThat(pageA.items().get(0).shopCode()).isEqualTo("ADT-A");

    var pageB = as(bearerB, "channel.operation.read", () -> service.strategies(null, null, null, 0, 50));
    assertThat(pageB.total()).isEqualTo(1);
    assertThat(pageB.items().get(0).id()).isEqualTo(strategyB.id());

    // ---- 详情：B 的策略在 A 的上下文里是 404（跨租户与不存在逐字相同）----
    var thrown = catchThrowable(() -> as(bearerA, "channel.operation.read", () -> service.strategy(strategyB.id())));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("分时策略不存在");

    // ---- 跑一轮：A 的任务只动 A 的活动 ----
    var runA = as(bearerA, "channel.operation.create",
        () -> service.run(new AdDaypartRunRequest(strategyA.id(), "APPLY", "MANUAL", "隔离测试"), bearerA));
    assertThat(runA.tasks()).hasSize(1);
    var taskA = runA.tasks().get(0);
    assertThat(taskA.lines()).isNotEmpty();
    assertThat(taskA.lines().get(0).campaignName()).contains("ADT-A");
    assertThat(taskA.lines().get(0).result()).isEqualTo("APPLIED");

    // 🔴 A 的活动预算改了，**B 的活动预算没动**
    assertThat(
            execOneAsTenant(a, "select daily_budget::text from ad_campaign where tenant_id = '" + a + "' and external_campaign_id = 'EXT-ADT-A'"))
        .isEqualTo("150.0000");
    assertThat(
            execOneAsTenant(b, "select daily_budget::text from ad_campaign where tenant_id = '" + b + "' and external_campaign_id = 'EXT-ADT-B'"))
        .isEqualTo("200.0000");

    // ---- 任务列表：A 看不到 B 的任务（此时 B 还没跑过，先跑一轮）----
    var runB = as(bearerB, "channel.operation.create",
        () -> service.run(new AdDaypartRunRequest(strategyB.id(), "APPLY", "MANUAL", "隔离测试"), bearerB));
    assertThat(runB.tasks()).hasSize(1);
    var tasksA = as(bearerA, "channel.operation.read", () -> service.tasks(null, null, 0, 50));
    assertThat(tasksA.total()).isEqualTo(1);
    assertThat(tasksA.items().get(0).id()).isEqualTo(taskA.id());

    var tasksB = as(bearerB, "channel.operation.read", () -> service.tasks(null, null, 0, 50));
    assertThat(tasksB.total()).isEqualTo(1);
    assertThat(tasksB.items().get(0).id()).isEqualTo(runB.tasks().get(0).id());

    // ---- 库里：A 的上下文读不到 B 的任何一行（以应用角色连，超级用户会绕过 RLS）----
    assertThat(countAsTenant(a, "select count(*) from ad_daypart_strategy where id='" + strategyB.id() + "'"))
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from ad_daypart_strategy_hour where strategy_id='" + strategyB.id() + "'"))
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from ad_daypart_baseline where strategy_id='" + strategyB.id() + "'"))
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from ad_daypart_task where strategy_id='" + strategyB.id() + "'"))
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from ad_daypart_task_line where task_id='" + runB.tasks().get(0).id() + "'"))
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from ad_daypart_recommendation where strategy_id='" + strategyB.id() + "'"))
        .isZero();
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("14.2.1.2 断言二：伪造 X-Tenant-Id 头无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seedShopAndCampaign(bearerA, a, "ADT-H-A", "100.0000");
    seedShopAndCampaign(bearerB, b, "ADT-H-B", "200.0000");
    var strategyA = seedStrategy(bearerA, a, "ADT-H-A", "ADP-HEADER-A");
    seedStrategy(bearerB, b, "ADT-H-B", "ADP-HEADER-B");

    // 🔴 拿 A 的 bearer，但把 X-Tenant-Id 伪造成 B ⇒ 仍然只能看到 A 的数据。
    var body =
        http.perform(
                get("/channel/ad-dayparts/strategies")
                    .header("Authorization", bearerA)
                    .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains(strategyA.id());
    assertThat(body).doesNotContain("ADP-HEADER-B");
    assertThat(body).doesNotContain(b.toString());

    // 🔴 换成 B 的租户号也一样读不到 B 的策略。
    var body2 =
        http.perform(
                get("/channel/ad-dayparts/strategies/" + strategyA.id())
                    .header("Authorization", bearerB)
                    .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body2).contains("分时策略不存在");
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("14.2.1.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void connectionPoolDoesNotLeakTenants() {
    seedShopAndCampaign(bearerA, a, "ADT-P-A", "100.0000");
    seedShopAndCampaign(bearerB, b, "ADT-P-B", "200.0000");
    var strategyA = seedStrategy(bearerA, a, "ADT-P-A", "ADP-POOL-A");
    var strategyB = seedStrategy(bearerB, b, "ADT-P-B", "ADP-POOL-B");

    for (int round = 0; round < 3; round++) {
      var pageA = as(bearerA, "channel.operation.read", () -> service.strategies(null, null, null, 0, 50));
      assertThat(pageA.total()).as("第 " + round + " 轮：A 只看到自己").isEqualTo(1);
      assertThat(pageA.items().get(0).id()).isEqualTo(strategyA.id());

      var pageB = as(bearerB, "channel.operation.read", () -> service.strategies(null, null, null, 0, 50));
      assertThat(pageB.total()).as("第 " + round + " 轮：B 只看到自己").isEqualTo(1);
      assertThat(pageB.items().get(0).id()).isEqualTo(strategyB.id());
    }

    // 🔴 池压到 1 之后连着跑两轮任务，预算各归各的。
    as(bearerA, "channel.operation.create",
        () -> service.run(new AdDaypartRunRequest(strategyA.id(), "APPLY", "MANUAL", "池测试"), bearerA));
    as(bearerB, "channel.operation.create",
        () -> service.run(new AdDaypartRunRequest(strategyB.id(), "APPLY", "MANUAL", "池测试"), bearerA));
    as(bearerA, "channel.operation.create",
        () -> service.run(new AdDaypartRunRequest(strategyA.id(), "APPLY", "MANUAL", "池测试"), bearerA));

    assertThat(
            execOneAsTenant(a, "select daily_budget::text from ad_campaign where tenant_id = '" + a + "' and external_campaign_id = 'EXT-ADT-P-A'"))
        .isEqualTo("150.0000");
    assertThat(
            execOneAsTenant(b, "select daily_budget::text from ad_campaign where tenant_id = '" + b + "' and external_campaign_id = 'EXT-ADT-P-B'"))
        .isEqualTo("300.0000");
  }
}
