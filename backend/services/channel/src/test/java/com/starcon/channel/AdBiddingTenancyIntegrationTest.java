package com.starcon.channel;

import static com.starcon.channel.AdBiddingModels.*;
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
 * 14.2.1.1 竞价管理 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（策略 / 建议批次 / 建议明细 / 调整任务 / 逐条明细 / 待下发队列 都算）
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
class AdBiddingTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  UUID a, b;
  String bearerA, bearerB;
  UUID shopA, shopB;

  /** 🔴 平台与站点注册表由 V1 迁移随代码发布（只读码表），测试直接用它。 */
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

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

  @Autowired AdBiddingService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer tenant-a-" + a;
    bearerB = "Bearer tenant-b-" + b;
    actors.put(bearerA, actor(user, a, List.of(grant("ALL", allPermissions))));
    actors.put(bearerB, actor(user, b, List.of(grant("ALL", allPermissions))));
    shopA = seedShop(a, "TENANT-SHOP-A");
    shopB = seedShop(b, "TENANT-SHOP-B");
  }

  <T> T as(String bearer, String permission, java.util.function.Supplier<T> work) {
    var who = actors.get(bearer);
    return access.run(who, permission, () -> work.get());
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

  UUID seedShop(UUID tenant, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + AMAZON + "','" + code + "','店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return id;
  }

  UUID seedKeyword(UUID tenant, UUID shopId, String text) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into ad_keyword (id,tenant_id,platform_id,shop_id,external_campaign_id,"
            + " external_keyword_id,keyword,internal_ad_type,platform_ad_type,bid,bid_currency,"
            + " status)"
            + " values ('" + id + "','" + tenant + "','" + AMAZON + "','" + shopId + "','CAMP-1','KW-"
            + text + "','" + text + "','AUTO_DISCOVERY','sponsored_products',2.0000,'USD','ACTIVE')");
    return id;
  }

  AdBiddingPolicyView newPolicy(String bearer, UUID tenant, String shopCode, String code) {
    return as(
        bearer,
        "channel.operation.create",
        () ->
            service.createPolicy(
                new AdBiddingPolicyRequest(
                    code, "策略 " + code, AMAZON.toString(), shopCode, "DOWN_ONLY", "USD", "SHOP",
                    null, null, null, new BigDecimal("0.30"), new BigDecimal("500.0000"),
                    new BigDecimal("0.25"), null, Boolean.FALSE, "")));
  }

  AdBiddingAdjustmentView adjust(
      String bearer, UUID tenant, UUID keywordId, AdBiddingPolicyView policy, String key) {
    return as(
        bearer,
        "channel.operation.create",
        () ->
            service.adjust(
                new AdBiddingAdjustmentRequest(
                    policy.id(), null, "USD", Boolean.FALSE,
                    List.of(new AdBiddingAdjustTarget(keywordId.toString(), new BigDecimal("2.5000"))),
                    key, "调整"),
                bearer));
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("14.2.1.1 断言一：两租户互不可见（策略 / 建议 / 调整任务 / 逐条明细 / 待下发队列）")
  void tenantsAreIsolated() {
    var keywordA = seedKeyword(a, shopA, "kw-a");
    var keywordB = seedKeyword(b, shopB, "kw-b");

    var policyA = newPolicy(bearerA, a, "TENANT-SHOP-A", "P-A");
    var policyB = newPolicy(bearerB, b, "TENANT-SHOP-B", "P-B");

    var adjustA = adjust(bearerA, a, keywordA, policyA, "bulk-a");
    var adjustB = adjust(bearerB, b, keywordB, policyB, "bulk-b");

    // B 的策略清单里只有 B 自己的
    var pageB = as(bearerB, "channel.operation.read", () -> service.policies(null, null, null, 0, 50));
    assertThat(pageB.items()).as("B 的租户上下文里只有 B 自己的策略").hasSize(1);
    assertThat(pageB.items().get(0).id()).isEqualTo(policyB.id());
    assertThat(pageB.items().get(0).id()).isNotEqualTo(policyA.id());

    // B 按 id 取 A 的策略 ⇒ 404
    assertThat(
            catchThrowable(
                () -> as(bearerB, "channel.operation.read", () -> service.policy(policyA.id()))))
        .as("B 按 id 取 A 的策略必须 404").isNotNull();

    // B 的调整任务列表里没有 A 的任务
    var adjustPageB =
        as(bearerB, "channel.operation.read", () -> service.adjustments(null, null, 0, 50));
    assertThat(adjustPageB.items()).hasSize(1);
    assertThat(adjustPageB.items().get(0).id()).isEqualTo(adjustB.id());
    assertThat(adjustPageB.items()).extracting("id").doesNotContain(adjustA.id());

    // B 按 id 取 A 的调整任务 ⇒ 404
    assertThat(
            catchThrowable(
                () ->
                    as(bearerB, "channel.operation.read", () -> service.adjustment(adjustA.id()))))
        .as("B 按 id 取 A 的调整任务必须 404").isNotNull();

    // B 的待下发队列里只有 B 自己的
    var dispatchB = as(bearerB, "channel.operation.read", () -> service.dispatches(null, null, 0, 50));
    assertThat(dispatchB.items()).hasSize(1);
    assertThat(dispatchB.items().get(0).adjustmentId()).isEqualTo(adjustB.id());

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from ad_bidding_policy where id='" + policyA.id() + "'"))
        .as("B 的上下文里直查 A 的策略必须读到零行").isZero();
    assertThat(countAsTenant(b,
            "select count(*) from ad_bidding_adjustment where id='" + adjustA.id() + "'"))
        .isZero();
    assertThat(countAsTenant(b,
            "select count(*) from ad_bidding_adjustment_item where adjustment_id='" + adjustA.id() + "'"))
        .isZero();
    assertThat(countAsTenant(b,
            "select count(*) from ad_bidding_dispatch where adjustment_id='" + adjustA.id() + "'"))
        .isZero();

    // 反向同理
    assertThat(countAsTenant(a, "select count(*) from ad_bidding_policy where id='" + policyB.id() + "'"))
        .isZero();
    assertThat(countAsTenant(a,
            "select count(*) from ad_bidding_adjustment where id='" + adjustB.id() + "'"))
        .isZero();
    assertThat(countAsTenant(a,
            "select count(*) from ad_bidding_dispatch where adjustment_id='" + adjustB.id() + "'"))
        .isZero();

    // 七张表都是 FORCE RLS
    for (String table :
        List.of(
            "ad_bidding_policy", "ad_bidding_suggestion", "ad_bidding_suggestion_sku",
            "ad_bidding_suggestion_item", "ad_bidding_adjustment",
            "ad_bidding_adjustment_item", "ad_bidding_dispatch")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from ad_bidding_policy where id='" + policyA.id() + "'"))
        .as("A 的上下文必须能直查 A 自己的策略（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a,
            "select count(*) from ad_bidding_adjustment where id='" + adjustA.id() + "'"))
        .isEqualTo(1);
    assertThat(countAsTenant(a,
            "select count(*) from ad_bidding_dispatch where adjustment_id='" + adjustA.id() + "'"))
        .isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("14.2.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    var keywordA = seedKeyword(a, shopA, "kw-forge-a");
    var keywordB = seedKeyword(b, shopB, "kw-forge-b");
    var policyA = newPolicy(bearerA, a, "TENANT-SHOP-A", "P-FORGE-A");
    var policyB = newPolicy(bearerB, b, "TENANT-SHOP-B", "P-FORGE-B");
    adjust(bearerA, a, keywordA, policyA, "bulk-forge-a");
    adjust(bearerB, b, keywordB, policyB, "bulk-forge-b");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的策略
    http.perform(get("/channel/ad-bidding/policies/" + policyB.id())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/ad-bidding/policies")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(policyA.id().toString()));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的
    http.perform(get("/channel/ad-bidding/policies")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(policyA.id().toString()));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/ad-bidding/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("14.2.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    var keywordA = seedKeyword(a, shopA, "kw-pool-a");
    var keywordB = seedKeyword(b, shopB, "kw-pool-b");
    var policyA = newPolicy(bearerA, a, "TENANT-SHOP-A", "P-POOL-A");
    var policyB = newPolicy(bearerB, b, "TENANT-SHOP-B", "P-POOL-B");
    adjust(bearerA, a, keywordA, policyA, "bulk-pool-a");
    adjust(bearerB, b, keywordB, policyB, "bulk-pool-b");

    for (int i = 0; i < 3; i++) {
      var pageA = as(bearerA, "channel.operation.read", () -> service.policies(null, null, null, 0, 50));
      assertThat(pageA.items()).hasSize(1);
      assertThat(pageA.items().get(0).id()).isEqualTo(policyA.id());

      var pageB = as(bearerB, "channel.operation.read", () -> service.policies(null, null, null, 0, 50));
      assertThat(pageB.items()).hasSize(1);
      assertThat(pageB.items().get(0).id()).isEqualTo(policyB.id());

      // 🔴 另一条查询形状也要干净（调整任务）
      var adjustA =
          as(bearerA, "channel.operation.read", () -> service.adjustments(null, null, 0, 50));
      assertThat(adjustA.items()).hasSize(1);
      assertThat(adjustA.items().get(0).policyId()).isEqualTo(policyA.id());

      var adjustB =
          as(bearerB, "channel.operation.read", () -> service.adjustments(null, null, 0, 50));
      assertThat(adjustB.items()).hasSize(1);
      assertThat(adjustB.items().get(0).policyId()).isEqualTo(policyB.id());

      // 🔴 第三条查询形状：待下发队列
      var dispatchA = as(bearerA, "channel.operation.read", () -> service.dispatches(null, null, 0, 50));
      assertThat(dispatchA.items()).hasSize(1);
      assertThat(dispatchA.items().get(0).policyId()).isEqualTo(policyA.id());

      var dispatchB = as(bearerB, "channel.operation.read", () -> service.dispatches(null, null, 0, 50));
      assertThat(dispatchB.items()).hasSize(1);
      assertThat(dispatchB.items().get(0).policyId()).isEqualTo(policyB.id());
    }
  }

  /**
   * 🔴 收尾：停掉本地桩与 Testcontainers 容器。
   *
   * <p>不回收容器的话，Ryuk 只在 JVM 退出时按 session 标签统一回收 —— 整个模块跑完之前容器一直活着
   * （容器内存 + Docker VM 把机器压进 swap ⇒ 全量跑时断言假红）。
   *
   * <p>顺序：先停外部服务，再停容器。
   */
  @AfterAll
  static void stopTestResources() {
    if (identity != null) identity.stop(0);
    pg.stop();
  }

  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
