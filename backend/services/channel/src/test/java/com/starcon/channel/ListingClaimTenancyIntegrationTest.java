package com.starcon.channel;

import static com.starcon.channel.ListingClaimModels.*;
import static com.starcon.channel.ListingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
 * 4.4.2.1 商品认领 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。本车道 4.3.2.1 实测踩过这个坑。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingClaimTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  // 🔴 平台与站点注册表由 V1 迁移随代码发布（只读码表），测试直接用它，不自己造一份。
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  UUID SITE_US;

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
    bearerA = "Bearer t-a-" + a;
    bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
  }

  @BeforeEach
  void seedGlobalCatalog() {
    // 平台与站点注册表由 V1 迁移随代码发布，这里只把要用的站点 id 读出来。
    SITE_US = marketplaceId(AMAZON, "US");
    assertThat(SITE_US).as("V1 迁移应当发布了 AMAZON 的美国站").isNotNull();
  }

  /** 从 V1 迁移发布的站点注册表里读一个站点 id。 */
  static UUID marketplaceId(UUID platformId, String code) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(
            "select id from marketplace where platform_id='" + platformId + "' and code='" + code + "'")) {
      return rs.next() ? UUID.fromString(rs.getString(1)) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
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
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired ListingClaimService service;
  @Autowired ListingService listService;
  @Autowired ListingDetailService detailService;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
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
   * 而超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
   * 那不是隔离被破坏，而是这个断言本身失效了。
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

  /** 以管理员连接读一行标量（只用于读全局码表这类不受 RLS 约束的事实）。 */
  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  UUID seedShop(UUID tenant, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    execAsTenant(tenant,
        "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status)"
            + " values ('" + tenant + "','" + id + "','" + SITE_US + "','ACTIVE')"
            + " on conflict (tenant_id,shop_id,marketplace_id) do update set status='ACTIVE'");
    return id;
  }

  ListingRow seedSource(AccessService.Access who, UUID shopId, String externalItemId, String title) {
    var row =
        as(who, "channel.operation.create",
            () ->
                listService.save(
                    new SaveListingRequest(
                        null, shopId, SITE_US, externalItemId, title, "Electronics > Audio", "cat-1",
                        "ON_SALE", "APPROVED", "", "IN_STOCK", null, null, null, "USD",
                        null, null,
                        List.of(new SaveVariantRequest(null, "v-1", "MAPPED", UUID.randomUUID(), null, "{}")),
                        null)));
    as(who, "channel.operation.create",
        () -> detailService.saveSnapshot(row.id().toString(),
            new ListingDetailModels.SaveContentSnapshotRequest(
                "en-US", title, "[\"卖点一\"]", "描述", "[\"https://img/1.jpg\"]", "",
                "[]", "{}", "{}", "SYNC", null, Boolean.TRUE)));
    return row;
  }

  List<ListingClaimFieldDecision> decisions() {
    return List.of(
        new ListingClaimFieldDecision("TITLE", "KEEP", null),
        new ListingClaimFieldDecision("BULLETS", "KEEP", null),
        new ListingClaimFieldDecision("PLATFORM_CATEGORY_ID", "SKIP", null));
  }

  ClaimListingRequest claimRequest(
      UUID sourceListingId, UUID targetShopId, UUID targetMarketplaceId) {
    return new ClaimListingRequest(
        sourceListingId, targetShopId, targetMarketplaceId, null, decisions(), "");
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("4.4.2.1 断言一：两租户互不可见（认领记录 / 目标草稿 / 逐字段决策都不可见）")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, "TEN-A-1");
    UUID shopB = seedShop(a, "TEN-A-2");
    UUID shopOther = seedShop(b, "TEN-B-1");
    var source = seedSource(actorA, shopA, "TEN-A", "A 的商品");
    var result =
        as(actorA, "channel.operation.create",
            () -> service.claim(claimRequest(source.id(), shopB, SITE_US), user));

    // B 看不到 A 的认领
    assertThat(as(actorB, "channel.operation.read", () -> service.list(null, null, 0, 50)))
        .as("B 的租户上下文里读不到 A 的认领").isEmpty();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.detail(result.claimId().toString()))))
        .isNotNull();

    // B 拿 A 的源 listing 发起认领 ⇒ 源不可见 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(actorB, "channel.operation.create",
                        () -> service.claim(claimRequest(source.id(), shopOther, SITE_US), user))))
        .as("B 不能拿 A 的 listing 认领").isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b,
            "select count(*) from listing_claim where id='" + result.claimId() + "'"))
        .as("B 的上下文里直查 A 的认领记录必须读到零行").isZero();
    assertThat(countAsTenant(b,
            "select count(*) from listing where id='" + result.targetListingId() + "'"))
        .as("B 的上下文里直查 A 的目标草稿必须读到零行").isZero();
    assertThat(countAsTenant(b,
            "select count(*) from listing_claim_field where claim_id='" + result.claimId() + "'"))
        .as("B 的上下文里直查 A 的逐字段决策必须读到零行").isZero();
    assertThat(countAsTenant(b,
            "select count(*) from listing_content_snapshot where listing_id='"
                + result.targetListingId() + "'"))
        .as("B 的上下文里直查 A 的目标内容快照必须读到零行").isZero();
    // 反向同理
    assertThat(countAsTenant(a,
            "select count(*) from listing where id='"
                + seedSource(actorB, shopOther, "TEN-B", "B 的商品").id() + "'"))
        .isZero();

    // 两张新表都是 FORCE RLS
    assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='listing_claim'"))
        .as("listing_claim 必须 FORCE RLS").isEqualTo("true");
    assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='listing_claim_field'"))
        .as("listing_claim_field 必须 FORCE RLS").isEqualTo("true");

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a,
            "select count(*) from listing_claim where id='" + result.claimId() + "'"))
        .as("A 的上下文必须能直查 A 自己的认领（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a,
            "select count(*) from listing_claim_field where claim_id='" + result.claimId() + "'"))
        .isEqualTo(3);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("4.4.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, "FORGE-A");
    UUID shopA2 = seedShop(a, "FORGE-A2");
    UUID shopB = seedShop(b, "FORGE-B");
    UUID shopB2 = seedShop(b, "FORGE-B2");
    var sourceA = seedSource(actorA, shopA, "FORGE-A", "A 的商品");
    var sourceB = seedSource(actorB, shopB, "FORGE-B", "B 的商品");
    var claimB =
        as(actorB, "channel.operation.create",
            () -> service.claim(claimRequest(sourceB.id(), shopB2, SITE_US), user));

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的认领
    http.perform(get("/channel/listing-claims/" + claimB.claimId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/listing-claims")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的（这里 A 还没认领 ⇒ 空）
    http.perform(get("/channel/listing-claims")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/listing-claims/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("4.4.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, "POOL-A-1");
    UUID shopA2 = seedShop(a, "POOL-A-2");
    UUID shopB = seedShop(b, "POOL-B-1");
    UUID shopB2 = seedShop(b, "POOL-B-2");
    var sourceA = seedSource(actorA, shopA, "POOL-A", "A 的商品");
    var sourceB = seedSource(actorB, shopB, "POOL-B", "B 的商品");
    var claimA =
        as(actorA, "channel.operation.create",
            () -> service.claim(claimRequest(sourceA.id(), shopA2, SITE_US), user));
    var claimB =
        as(actorB, "channel.operation.create",
            () -> service.claim(claimRequest(sourceB.id(), shopB2, SITE_US), user));

    for (int i = 0; i < 3; i++) {
      // A 读 A 的、B 读 B 的，各三轮
      assertThat(as(actorA, "channel.operation.read", () -> service.list(null, null, 0, 50)))
          .hasSize(1);
      assertThat(as(actorA, "channel.operation.read", () -> service.list(null, null, 0, 50)).get(0)
              .claimId())
          .isEqualTo(claimA.claimId());
      assertThat(as(actorA, "channel.operation.read", () -> service.list(null, null, 0, 50)).get(0)
              .sourceTitle())
          .isEqualTo("A 的商品");
      assertThat(as(actorB, "channel.operation.read", () -> service.list(null, null, 0, 50)))
          .hasSize(1);
      assertThat(as(actorB, "channel.operation.read", () -> service.list(null, null, 0, 50)).get(0)
              .claimId())
          .isEqualTo(claimB.claimId());
      assertThat(as(actorB, "channel.operation.read", () -> service.list(null, null, 0, 50)).get(0)
              .sourceTitle())
          .isEqualTo("B 的商品");

      // 目标草稿也必须跟着租户走：串了就会读到别人的草稿
      assertThat(as(actorA, "channel.operation.read",
              () -> listService.load(claimA.targetListingId().toString()))
              .title())
          .isEqualTo("A 的商品");
      assertThat(as(actorB, "channel.operation.read",
              () -> listService.load(claimB.targetListingId().toString()))
              .title())
          .isEqualTo("B 的商品");
    }
  }

  /**
   * 🔴 收尾：停掉本地桩与 Testcontainers 容器。
   *
   * <p>不回收容器的话，Ryuk 只在 JVM 退出时按 session 标签统一回收 —— 整个模块跑完之前容器一直活着
   * （实测 channel 一个服务就能堆到 110+ 个 postgres 容器），容器内存 + Docker VM 把机器压进 swap，
   * JVM 被卡住十几分钟 ⇒ 全量跑时断言假红、SSL 握手失败。
   *
   * <p>顺序：先停外部服务，再停容器。反了会出现「容器已停但还有 in-flight 请求打进来」。
   */
  @AfterAll
  static void stopTestResources() {
    if (identity != null) identity.stop(0);
    pg.stop();
  }
}
