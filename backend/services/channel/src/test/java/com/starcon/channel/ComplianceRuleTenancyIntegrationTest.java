package com.starcon.channel;

import static com.starcon.channel.ComplianceRuleModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 16.2.1.1 规则库 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（规则 / 版本 / 词条 / 适用范围四张表都不可见）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系），
 *       <b>并断言行确实落在会话租户名下</b>（不是只断言 A 看不见）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComplianceRuleTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\","
          + "\"channel.sync.read\",\"channel.sync.trigger\"],\"scopeMode\":\"ALL\"}]";

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
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 🔴 关掉 identity 这台静态桩。
   *
   * <p>它是<b>静态</b>桩：不起就停 ⇒ 端口被占住整整一轮 JVM 生命周期。
   */
  @AfterAll
  static void stopStubs() {
    if (identity != null) identity.stop(0);
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

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired ComplianceRuleService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access actor, String permission, Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  static void execAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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

  UUID seedShop(UUID tenantId, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenantId,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenantId + "','" + id
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')");
    return id;
  }

  /**
   * 在某个租户下造一整套规则数据（规则 + 版本 + 词条 + 适用范围）。
   *
   * @return 规则 id（版本 id 用 {@link #versionIdOf} 单独取）
   */
  String seed(AccessService.Access actor, UUID tenantId, UUID shopId, String code) {
    String ruleId =
        as(
                actor,
                "channel.operation.create",
                () ->
                    service.createRule(
                            new ComplianceRuleCreateInput(code, "PROHIBITED_WORD", "规则 " + code, "说明"))
                        .rule()
                        .ruleId());
    as(
        actor,
        "channel.operation.create",
        () ->
            service.createVersion(
                ruleId,
                new ComplianceRuleVersionInput(
                    "BLOCKING",
                    "版本标题",
                    "版本说明",
                    null,
                    "首版",
                    List.of(new ComplianceRuleTermInput("违禁词-" + code, "GENERAL", "")),
                    null,
                    List.of(
                        new ComplianceRuleScopeInput(
                            AMAZON.toString(), null, shopId.toString())))));
    return ruleId;
  }

  /** 取某条规则的当前生效版本 id。 */
  String versionIdOf(AccessService.Access actor, String ruleId) {
    return as(
            actor,
            "channel.operation.read",
            () -> service.versions(ruleId, 0, 50).items().get(0).versionId());
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("16.2.1.1 断言一：两租户互不可见（四张表都不可见）")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, "TEN-A-1");
    UUID shopB = seedShop(b, "TEN-B-1");

    String ruleA = seed(actorA, a, shopA, "TEN-A-RULE");
    seed(actorB, b, shopB, "TEN-B-RULE");
    String versionA = versionIdOf(actorA, ruleA);

    // B 读不到 A 的任何东西
    assertThat(as(actorB, "channel.operation.read", () -> service.rules(null, null, null, null, 0, 50)).items())
        .extracting(ComplianceRule::ruleId)
        .doesNotContain(ruleA);
    // 🔴 按 id 直取 A 的版本 ⇒ 404（B 的租户上下文里根本读不到这一行）。
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.versionDetail(ruleA, versionA))))
        .as("B 按 id 直取 A 的版本必须失败")
        .isNotNull();
    // 🔴 按 id 直取 A 的规则 ⇒ 404
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> service.rule(ruleA))))
        .as("B 按 id 直取 A 的规则必须失败").isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from compliance_rule where id='" + ruleA + "'"))
        .as("B 的上下文里直查 A 的规则必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from compliance_rule_version where id='" + versionA + "'"))
        .as("B 的上下文里直查 A 的版本必须读到零行").isZero();
    assertThat(countAsTenant(b, "select count(*) from compliance_rule")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from compliance_rule_version")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from compliance_rule_term")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from compliance_rule_scope")).isEqualTo(1);

    // 四张表都是 FORCE RLS
    for (String table :
        List.of(
            "compliance_rule",
            "compliance_rule_version",
            "compliance_rule_term",
            "compliance_rule_scope")) {
      assertThat(
              scalar(
                  "select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from compliance_rule")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from compliance_rule_version")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from compliance_rule_term")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from compliance_rule_scope")).isEqualTo(1);
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("16.2.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, "FORGE-A");
    UUID shopB = seedShop(b, "FORGE-B");
    String versionB = seed(actorB, b, shopB, "FORGE-B-RULE");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的规则
    http.perform(get("/channel/compliance-rules/rules/" + versionB)
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/compliance-rules/rules")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/compliance-rules/capabilities").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());

    // A 建一条之后，A 的会话 + B 的头仍然只看到 A 自己的
    seed(actorA, a, shopA, "FORGE-A-RULE");
    http.perform(get("/channel/compliance-rules/rules")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    // 🔴 **并断言行确实落在会话租户名下**（不是只断言 A 看不见 B）
    assertThat(scalar("select count(*) from compliance_rule where tenant_id='" + a + "'"))
        .as("A 会话写入的行必须落在 A 的租户名下")
        .isEqualTo("1");
    assertThat(scalar("select count(*) from compliance_rule where tenant_id='" + b + "'"))
        .as("B 会话写入的行必须落在 B 的租户名下")
        .isEqualTo("1");
    assertThat(countAsTenant(a, "select count(*) from compliance_rule where tenant_id='" + b + "'"))
        .as("A 的上下文里直查 B 的行必须读到零行")
        .isZero();
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("16.2.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, "POOL-A");
    UUID shopB = seedShop(b, "POOL-B");
    String ruleA = seed(actorA, a, shopA, "POOL-A-RULE");
    String ruleB = seed(actorB, b, shopB, "POOL-B-RULE");

    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read", () -> service.rules(null, null, null, null, 0, 50)).items())
          .as("第 " + (i + 1) + " 轮：A 只看到 A 的规则")
          .extracting(ComplianceRule::ruleId)
          .contains(ruleA)
          .doesNotContain(ruleB);
      assertThat(as(actorB, "channel.operation.read", () -> service.rules(null, null, null, null, 0, 50)).items())
          .as("第 " + (i + 1) + " 轮：B 只看到 B 的规则")
          .extracting(ComplianceRule::ruleId)
          .contains(ruleB)
          .doesNotContain(ruleA);
      assertThat(as(actorA, "channel.operation.read", () -> service.effective(null, null, null, 0, 50)).items())
          .as("第 " + (i + 1) + " 轮：A 的生效规则集里只有 A 的")
          .extracting(e -> e.rule().ruleId())
          .contains(ruleA)
          .doesNotContain(ruleB);
    }

    // 池里那条连接归还后，租户上下文必须被清掉：直查计数时不能带上前一个租户的行
    assertThat(countAsTenant(b, "select count(*) from compliance_rule")).isEqualTo(1);
    assertThat(countAsTenant(b, "select count(*) from compliance_rule_version")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from compliance_rule")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from compliance_rule_version")).isEqualTo(1);
  }
}
