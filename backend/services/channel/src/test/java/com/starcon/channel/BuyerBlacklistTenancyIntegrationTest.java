package com.starcon.channel;

import static com.starcon.channel.BuyerBlacklistModels.*;
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
 * 16.5.1.1 黑名单 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（登记 / 状态事件 / 申诉 / 拦截标记都不可见）
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
class BuyerBlacklistTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static HttpServer identity;
  static BuyerBlacklistTradeStub trade;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

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

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
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
      trade = new BuyerBlacklistTradeStub();
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
    trade.reset();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("TRADE_URL", trade::base);
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired BuyerBlacklistService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以<b>应用角色 channel_app</b>（非超级用户）直查一行计数 —— 超级用户绕过 RLS 会让断言永远「通过」。 */
  static long countAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(statement)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 播种一个店铺（按租户）。 */
  void seedShop(UUID tenantId, String code) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(
          "insert into shop (tenant_id,id,platform_id,code,name) values ('"
              + tenantId
              + "','"
              + UUID.randomUUID()
              + "','"
              + AMAZON
              + "','"
              + code
              + "','测试店铺 "
              + code
              + "')");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 在某个租户下登记一条黑名单。 */
  BuyerBlacklistEntryView register(
      AccessService.Access who, String bearer, String name, String phone, String email) {
    trade.putProfile(BuyerRiskScoreSourcePort.buyerKey(name, phone, email), name, phone, email, "STANDARD", false, "");
    return as(
        who,
        "channel.operation.create",
        () ->
            service.register(
                bearer,
                new BuyerBlacklistRegisterInput(
                    name, phone, email, "ALL_SHOPS", "", "HIGH", "多租户测试", ""),
                "tester"));
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("16.5.1.1 断言一：两租户互不可见（登记 / 状态事件 / 申诉 / 拦截标记都不可见）")
  void tenantsAreIsolated() {
    BuyerBlacklistEntryView entryA =
        register(actorA, bearerA, "Alice Smith", "+1-555-0100", "alice@example.com");
    register(actorB, bearerB, "Bob Jones", "+1-555-0200", "bob@example.com");

    // A 造一条拦截标记 + 一条申诉（这两张表也必须隔离）
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.check(
                bearerA,
                new BuyerBlacklistCheckInput(
                    "Alice Smith", "+1-555-0100", "alice@example.com", "", "SO-A-1")));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.submitAppeal(
                entryA.entryId().toString(),
                new BuyerBlacklistAppealInput("申诉一下"),
                "tester"));

    // B 读不到 A 的登记
    assertThat(as(actorB, "channel.operation.read", () -> service.entries(null, null, null, null, null, null, 0, 50)).items())
        .as("B 的租户上下文里读不到 A 的黑名单")
        .extracting(BuyerBlacklistEntryView::entryId)
        .doesNotContain(entryA.entryId());
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.entry(entryA.entryId().toString()))))
        .as("B 按 id 直取 A 的登记必须失败")
        .isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from buyer_blacklist_entry where id='"
            + entryA.entryId() + "'"))
        .as("B 的上下文里直查 A 的登记必须读到零行")
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from buyer_blacklist_status_event where entry_id='"
            + entryA.entryId() + "'"))
        .as("B 的上下文里直查 A 的状态事件必须读到零行")
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from buyer_blacklist_appeal where entry_id='"
            + entryA.entryId() + "'"))
        .as("B 的上下文里直查 A 的申诉单必须读到零行")
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from buyer_blacklist_order_flag"))
        .as("B 的上下文里读不到 A 的拦截标记")
        .isZero();

    // 四张表都是 FORCE RLS
    for (String table :
        List.of(
            "buyer_blacklist_entry",
            "buyer_blacklist_status_event",
            "buyer_blacklist_appeal",
            "buyer_blacklist_order_flag")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from buyer_blacklist_entry where id='"
            + entryA.entryId() + "'"))
        .as("A 的上下文必须能直查 A 自己的登记（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from buyer_blacklist_status_event where entry_id='"
            + entryA.entryId() + "'"))
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from buyer_blacklist_appeal where entry_id='"
            + entryA.entryId() + "'"))
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from buyer_blacklist_order_flag"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("16.5.1.1 断言一（续）：同一买家在 B 家读不到 A 家的黑名单")
  void sameBuyerKeyIsScopedPerTenant() {
    // 两个租户用**同一组收件人三件套** ⇒ 主体键完全相同
    BuyerBlacklistEntryView entryA =
        register(actorA, bearerA, "Same Person", "+1-555-9999", "same@example.com");
    register(actorB, bearerB, "Same Person", "+1-555-9999", "same@example.com");

    assertThat(entryA.buyerKey())
        .as("两个租户的同一买家主体键相同")
        .isEqualTo(
            as(actorB, "channel.operation.read", () -> service.entries(null, null, null, null, null, null, 0, 50))
                .items()
                .get(0)
                .buyerKey());

    assertThat(countAsTenant(b, "select count(*) from buyer_blacklist_entry where buyer_key='"
            + entryA.buyerKey() + "' and id='"
            + entryA.entryId() + "'"))
        .as("B 的上下文里读不到 A 那个同主体键的登记")
        .isZero();
    assertThat(countAsTenant(b, "select count(*) from buyer_blacklist_entry where buyer_key='"
            + entryA.buyerKey() + "'"))
        .as("同主体键在 B 租户下只对应 B 自己那一条")
        .isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from buyer_blacklist_entry"))
        .as("B 只看得到自己那一条")
        .isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("16.5.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    BuyerBlacklistEntryView entryB =
        register(actorB, bearerB, "Bob Jones", "+1-555-0200", "bob@example.com");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的登记
    http.perform(get("/channel/buyer-blacklist/entries/" + entryB.entryId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/buyer-blacklist/entries")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/buyer-blacklist/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());

    // A 自己登记一条之后，A 的会话 + B 的头仍然只看到 A 自己的
    register(actorA, bearerA, "Alice Smith", "+1-555-0100", "alice@example.com");
    http.perform(get("/channel/buyer-blacklist/entries")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("16.5.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    BuyerBlacklistEntryView entryA =
        register(actorA, bearerA, "Alice Smith", "+1-555-0100", "alice@example.com");
    BuyerBlacklistEntryView entryB =
        register(actorB, bearerB, "Bob Jones", "+1-555-0200", "bob@example.com");

    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read", () -> service.entries(null, null, null, null, null, null, 0, 50)).items())
          .as("第 " + (i + 1) + " 轮：A 只看到 A 的登记")
          .extracting(BuyerBlacklistEntryView::entryId)
          .containsExactly(entryA.entryId());
      assertThat(as(actorB, "channel.operation.read", () -> service.entries(null, null, null, null, null, null, 0, 50)).items())
          .as("第 " + (i + 1) + " 轮：B 只看到 B 的登记")
          .extracting(BuyerBlacklistEntryView::entryId)
          .containsExactly(entryB.entryId());
      // 详情也跟着租户走
      assertThat(as(actorA, "channel.operation.read", () -> service.entry(entryA.entryId().toString()))
              .entry()
              .buyerKey())
          .isEqualTo(entryA.buyerKey());
    }

    // 池里那条连接归还后，租户上下文必须被清掉：直查计数时不能带上前一个租户的行
    assertThat(countAsTenant(b, "select count(*) from buyer_blacklist_entry"))
        .as("B 的上下文里只能看到 B 自己的登记")
        .isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from buyer_blacklist_entry"))
        .isEqualTo(1);
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
    if (trade != null) trade.close();
    pg.stop();
  }
}
