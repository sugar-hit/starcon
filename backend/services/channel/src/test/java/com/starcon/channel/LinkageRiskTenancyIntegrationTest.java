package com.starcon.channel;

import static com.starcon.channel.LinkageRiskModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
 * 16.5.2.2 关联风险 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（九张表都不可见）</li>
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）</li>
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）</li>
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LinkageRiskTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static LinkageRiskIdentityStub identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final String AMAZON = "11111111-1111-1111-1111-111111111101";
  static final String SHOPIFY = "11111111-1111-1111-1111-111111111102";

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      List.of(
          "channel.operation.read",
          "channel.operation.create",
          "channel.operation.update",
          "channel.operation.delete");

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenantId,
        "Test",
        UUID.randomUUID(),
        null,
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
      identity = new LinkageRiskIdentityStub();
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
    identity.register(bearerA, actorA);
    identity.register(bearerB, actorB);
    identity.reset();
    execSuper(
        "truncate linkage_alert_action,linkage_remediation,linkage_alert,linkage_cluster_shop,"
            + "linkage_cluster,linkage_shop_identifier,linkage_identifier,"
            + "linkage_policy_dimension,linkage_policy");
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", identity::base);
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired LinkageRiskService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以**应用账号 channel_app** 直查一行计数（超级用户绕过 RLS 会让这个断言永远「通过」）。 */
  static long countAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.read',false)");
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

  static void execSuper(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以超级用户 + 租户上下文执行一条 SQL（播种店铺要过 owner 触发器）。 */
  static void execAsTenant(UUID tenantId, String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  UUID seedShop(UUID tenantId, String code, String platformId) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenantId,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenantId
            + "','"
            + id
            + "','"
            + platformId
            + "','"
            + code
            + "','测试店铺 "
            + code
            + "')");
    return id;
  }

  /** 在某个租户造一整套数据：策略 + 两个共享设备的店铺 + 标识 + 边 + 一次扫描。 */
  void seedTenant(UUID tenantId, AccessService.Access who, String codePrefix) {
    as(
        who,
        "channel.operation.update",
        () ->
            service.savePolicy(
                new LinkageRiskPolicyInput(
                    codePrefix + " 关联策略",
                    "ACTIVE",
                    2,
                    "TENANT",
                    List.of(
                        new LinkageRiskPolicyDimensionInput("DEVICE", "HIGH", 2, "测试依据", true))),
                who));
    UUID s1 = seedShop(tenantId, codePrefix + "-S1", AMAZON);
    UUID s2 = seedShop(tenantId, codePrefix + "-S2", SHOPIFY);
    var identifier =
        as(
            who,
            "channel.operation.create",
            () ->
                service.createIdentifier(
                    new LinkageRiskIdentifierInput(
                        "DEVICE", codePrefix + "-DEV", codePrefix + "-DEV", "测试登记", null),
                    who));
    for (UUID shop : List.of(s1, s2)) {
      as(
          who,
          "channel.operation.create",
          () ->
              service.linkShop(
                  identifier.identifierId().toString(),
                  new LinkageRiskShopLinkInput(shop, "MANUAL", "测试登记", null),
                  who));
    }
    as(who, "channel.operation.create", () -> service.scan("Bearer " + codePrefix, who));
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("16.5.2.2 断言一：两租户互不可见（九张表都不可见）")
  void tenantsAreIsolated() {
    seedTenant(a, actorA, "A");
    seedTenant(b, actorB, "B");

    // ---- ① 内容级：A 只看到 A 的策略与提示 ----
    assertThat(as(actorA, "channel.operation.read", service::policy).policyName())
        .isEqualTo("A 关联策略");
    assertThat(as(actorB, "channel.operation.read", service::policy).policyName())
        .isEqualTo("B 关联策略");

    var alertsA = as(actorA, "channel.operation.read", () -> service.alerts(null, null, 0, 50));
    var alertsB = as(actorB, "channel.operation.read", () -> service.alerts(null, null, 0, 50));
    assertThat(alertsA.total()).isEqualTo(1);
    assertThat(alertsB.total()).isEqualTo(1);
    // 🔴 摘要里带的是**店铺代码**（A-S1 / B-S1），能区分两个租户的内容
    assertThat(alertsA.items().get(0).summary()).contains("A-S1");
    assertThat(alertsA.items().get(0).summary()).doesNotContain("B-S1");
    assertThat(alertsB.items().get(0).summary()).contains("B-S1");
    assertThat(alertsB.items().get(0).summary()).doesNotContain("A-S1");
    assertThat(alertsA.items().get(0).alertId()).isNotEqualTo(alertsB.items().get(0).alertId());

    var identifiersA =
        as(actorA, "channel.operation.read", () -> service.identifiers(null, null, 0, 50));
    var identifiersB =
        as(actorB, "channel.operation.read", () -> service.identifiers(null, null, 0, 50));
    assertThat(identifiersA.items()).hasSize(1);
    assertThat(identifiersB.items()).hasSize(1);
    assertThat(identifiersA.items().get(0).identifierKey()).isEqualTo("A-DEV");
    assertThat(identifiersB.items().get(0).identifierKey()).isEqualTo("B-DEV");

    // ---- ② 数量级：每张表在对方上下文里都是零行 ----
    for (String table :
        List.of(
            "linkage_policy",
            "linkage_policy_dimension",
            "linkage_identifier",
            "linkage_shop_identifier",
            "linkage_cluster",
            "linkage_cluster_shop",
            "linkage_alert",
            "linkage_alert_action",
            "linkage_remediation")) {
      // 🔴 隔离的准确口径：在 A 的上下文里**一条 tenant_id=B 的行都读不到**；
      //    同时 A 自己的行必须读得到（否则「0」可能只是查询本身失效）。
      assertThat(countAsTenant(a, "select count(*) from " + table + " where tenant_id='" + b + "'"))
          .as("A 的上下文里读不到 B 的 " + table)
          .isZero();
      assertThat(countAsTenant(b, "select count(*) from " + table + " where tenant_id='" + a + "'"))
          .as("B 的上下文里读不到 A 的 " + table)
          .isZero();
      assertThat(countAsTenant(a, "select count(*) from " + table))
          .as("A 的上下文里必须能读到 A 自己的 " + table + "（证明上面的 0 是隔离，不是查询失效）")
          .isPositive();
      assertThat(countAsTenant(b, "select count(*) from " + table))
          .as("B 的上下文里必须能读到 B 自己的 " + table)
          .isPositive();
    }

    // ---- ③ 引用级：B 读不到 A 的提示细节（按 id 直查也拿不到）----
    UUID alertA =
        UUID.fromString(
            scalar("select id::text from linkage_alert where tenant_id='" + a + "' limit 1"));
    assertThat(countAsTenant(b, "select count(*) from linkage_alert where id='" + alertA + "'"))
        .as("B 读不到 A 的风险提示")
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from linkage_alert where id='" + alertA + "'"))
        .isPositive();

    // ---- ④ 店铺也互不可见（图谱节点来源）----
    assertThat(countAsTenant(a, "select count(*) from shop")).isPositive();
    assertThat(countAsTenant(b, "select count(*) from shop")).isPositive();

    // ---- ⑤ 九张表都是 FORCE RLS ----
    for (String table :
        List.of(
            "linkage_policy",
            "linkage_policy_dimension",
            "linkage_identifier",
            "linkage_shop_identifier",
            "linkage_cluster",
            "linkage_cluster_shop",
            "linkage_alert",
            "linkage_alert_action",
            "linkage_remediation")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("16.5.2.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seedTenant(a, actorA, "A");
    seedTenant(b, actorB, "B");

    // A 的会话 + B 的头 ⇒ 读到的仍是 A 的数据
    http.perform(get("/channel/linkage-risk/policy")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.policyName").value("A 关联策略"));

    http.perform(get("/channel/linkage-risk/alerts")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(
            jsonPath("$.items[0].summary").value(org.hamcrest.Matchers.containsString("A-S1")));

    http.perform(get("/channel/linkage-risk/identifiers")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].identifierKey").value("A-DEV"));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/linkage-risk/policy").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("16.5.2.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    seedTenant(a, actorA, "A");
    seedTenant(b, actorB, "B");
    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read", service::policy).policyName())
          .as("第 " + (i + 1) + " 轮：A 只看到 A 的策略")
          .isEqualTo("A 关联策略");
      assertThat(as(actorB, "channel.operation.read", service::policy).policyName())
          .as("第 " + (i + 1) + " 轮：B 只看到 B 的策略")
          .isEqualTo("B 关联策略");
      assertThat(
              as(actorA, "channel.operation.read", () -> service.identifiers(null, null, 0, 50))
                  .items()
                  .get(0)
                  .identifierKey())
          .isEqualTo("A-DEV");
      assertThat(
              as(actorB, "channel.operation.read", () -> service.identifiers(null, null, 0, 50))
                  .items()
                  .get(0)
                  .identifierKey())
          .isEqualTo("B-DEV");
    }

    // 池里那条连接归还后，租户上下文必须被清掉
    assertThat(countAsTenant(b, "select count(*) from linkage_identifier")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from linkage_identifier")).isEqualTo(1);
  }
}
