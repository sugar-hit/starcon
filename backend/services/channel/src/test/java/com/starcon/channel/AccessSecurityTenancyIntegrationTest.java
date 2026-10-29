package com.starcon.channel;

import static com.starcon.channel.AccessSecurityModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
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
 * 16.5.2.1 登录与访问安全 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（策略 / 白名单 / MFA 登记 / 告警 / 登录审计 / 凭据访问审计都不可见）
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
class AccessSecurityTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\","
          + "\"channel.connection.read\",\"identity.audit.read\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      List.of(
          "channel.operation.read",
          "channel.operation.create",
          "channel.operation.update",
          "channel.operation.delete",
          "channel.connection.read",
          "identity.audit.read");

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

  @Autowired AccessSecurityService service;
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
      s.execute("select set_config('app.permission','identity.audit.read',false)");
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

  AccessSecurityPolicyInput policy(String name) {
    return new AccessSecurityPolicyInput(
        name, false, "TOTP", 0, null, 480, 1440, 5, true, true, "DISABLED", 15, 3, 3, null);
  }

  /** 在两个租户各造一份**同名但内容可区分**的数据。 */
  void seedBoth() {
    as(actorA, "channel.operation.update", () -> service.savePolicy(policy("A 租户策略"), actorA));
    as(actorB, "channel.operation.update", () -> service.savePolicy(policy("B 租户策略"), actorB));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.addAllowlist(
                new AccessSecurityIpAllowlistInput(
                    "10.0.0.0/8", "A 网段", "TENANT", null, "ACTIVE", null, null),
                actorA));
    as(
        actorB,
        "channel.operation.create",
        () ->
            service.addAllowlist(
                new AccessSecurityIpAllowlistInput(
                    "10.0.0.0/8", "B 网段", "TENANT", null, "ACTIVE", null, null),
                actorB));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.register(
                new AccessSecurityMfaRegistrationInput(user, null, "TOTP", "A 因子", "vault://a"),
                actorA));
    as(
        actorB,
        "channel.operation.create",
        () ->
            service.register(
                new AccessSecurityMfaRegistrationInput(user, null, "TOTP", "B 因子", "vault://b"),
                actorB));
  }

  /** 只在 A 租户造数据（用真实的服务路径或真实 SQL，两边都要有凭据）。 */
  void seedAOnly() {
    // ① MFA 挑战：只有 A 有
    var registrationA =
        as(
            actorA,
            "identity.audit.read",
            () -> service.registrations(null, "PENDING", 0, 50)).items().get(0);
    as(actorA, "channel.operation.update", () -> service.activateRegistration(registrationA.registrationId().toString(), actorA));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.issueChallenge(
                new AccessSecurityMfaChallengeInput(
                    registrationA.registrationId(), "TOTP", "AUTHENTICATOR_APP", "app", 300),
                actorA));

    // ② 凭据访问审计：只有 A 有（凭据也只属于 A）
    UUID credential =
        UUID.fromString(
            execSuperReturning(
                "insert into credential_ref(id,tenant_id,provider,external_ref,token_type,granted_scopes,"
                    + " authorized_at,status) values('"
                    + UUID.randomUUID()
                    + "','"
                    + a
                    + "','file://./.starcon-secrets/t1','file://./.starcon-secrets/t1/token','OAUTH2',"
                    + "'orders.read',"
                    // ⚠️ authorized_at 必须用**数据库的** now()，不能用 Java 墙钟：
                    //    约束 credential_window 是 CHECK(authorized_at <= now() + interval '5 minutes')，
                    //    而 PG 的 now() = transaction_timestamp()（**事务开始时刻**）。
                    //    Java 墙钟是「语句执行时刻」，两者在长事务里会分叉 —— 机器负载高时
                    //    本类曾跑到 919s/3 例，事务一旦跨过 5 分钟，墙钟值就会超出
                    //    「事务开始 + 5 分钟」而违约（2026-10-26 r102 L2 实测假红）。
                    //    用 DB 的 now() ⇒ 与约束同一时间基准，与机器负载彻底解耦。
                    + "now(),"
                    + "'ACTIVE') returning id::text"));
    as(
        actorA,
        "channel.connection.read",
        () ->
            service.controlledAccess(
                new AccessSecurityCredentialAccessInput(credential, "A 租户的受控访问"), actorA));

    // ③ 登录审计 + 告警 + 处置留痕：只有 A 有
    execSuper(
        "insert into access_security_login_audit(id,tenant_id,source_event_id,event_type,outcome,"
            + " actor_name,level,request_id,summary,occurred_at,source_ip,source_ip_available,anomaly,"
            + " anomaly_code,note,created_at) values('"
            + UUID.randomUUID()
            + "','"
            + a
            + "','"
            + UUID.randomUUID()
            + "','LOGIN','SUCCESS','alice','INFO','r1','登录成功',now(),'',false,false,'','',now())");
    UUID alertId = UUID.randomUUID();
    execSuper(
        "insert into access_security_alert(id,tenant_id,alert_type,severity,status,subject_kind,"
            + " subject_value,dedup_key,title,summary,event_count,detected_at,version,created_at,updated_at)"
            + " values('"
            + alertId
            + "','"
            + a
            + "','FAILED_LOGIN_BURST','HIGH','NEW','USERNAME','alice','FAILED_LOGIN_BURST|alice',"
            + "'测试告警','摘要',1,now(),0,now(),now())");
    execSuper(
        "insert into access_security_alert_action(id,tenant_id,alert_id,action,from_status,to_status,"
            + " actor_name,reason,created_at) values('"
            + UUID.randomUUID()
            + "','"
            + a
            + "','"
            + alertId
            + "','ACKNOWLEDGE','NEW','ACKNOWLEDGED','tester','已确认',now())");
  }

  /** 以超级用户执行一条 SQL 并返回第一列（播种用，绕开 RLS）。 */
  static String execSuperReturning(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以超级用户执行一条语句（播种用，绕开 RLS）。 */
  static void execSuper(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("16.5.2.1 断言一：两租户互不可见（九张表都不可见）")
  void tenantsAreIsolated() {
    seedBoth();
    seedAOnly();

    // ---- ① 内容级：B 的上下文里读到的必须是 B 自己的内容 ----
    assertThat(as(actorB, "identity.audit.read", service::policy).policyName())
        .as("B 读到的必须是 B 自己的策略")
        .isEqualTo("B 租户策略");
    assertThat(as(actorA, "identity.audit.read", service::policy).policyName())
        .isEqualTo("A 租户策略");
    assertThat(as(actorB, "identity.audit.read", () -> service.allowlist(null, 0, 50)).items())
        .extracting(AccessSecurityIpAllowlistView::label)
        .containsExactly("B 网段");
    assertThat(as(actorA, "identity.audit.read", () -> service.allowlist(null, 0, 50)).items())
        .extracting(AccessSecurityIpAllowlistView::label)
        .containsExactly("A 网段");
    assertThat(as(actorB, "identity.audit.read", () -> service.registrations(null, null, 0, 50)).items())
        .extracting(AccessSecurityMfaRegistrationView::factorLabel)
        .containsExactly("B 因子");

    // ---- ② 数量级：只有 A 有数据的表，B 必须读到零行 ----
    for (String table :
        List.of(
            "access_security_mfa_challenge",
            "access_security_login_audit",
            "access_security_alert",
            "access_security_alert_action",
            "access_security_credential_access")) {
      assertThat(countAsTenant(b, "select count(*) from " + table))
          .as("B 的上下文里读不到 A 的 " + table)
          .isZero();
      assertThat(countAsTenant(a, "select count(*) from " + table))
          .as("A 的上下文里必须能读到 A 自己的 " + table + "（证明上面的 0 是隔离，不是查询失效）")
          .isPositive();
    }

    // ---- ③ 引用级：B 读不到 A 的策略变更留痕（A 的 policy_id 在 B 的上下文里是零命中）----
    UUID policyA =
        UUID.fromString(
            execSuperReturning(
                "select id::text from access_security_policy where tenant_id='" + a + "'"));
    assertThat(countAsTenant(b, "select count(*) from access_security_policy_audit where target_id='"
            + policyA + "'"))
        .as("B 读不到 A 的策略变更留痕")
        .isZero();
    assertThat(countAsTenant(a, "select count(*) from access_security_policy_audit where target_id='"
            + policyA + "'"))
        .isPositive();

    // ---- ④ 九张表都是 FORCE RLS ----
    for (String table :
        List.of(
            "access_security_policy",
            "access_security_policy_audit",
            "access_security_mfa_registration",
            "access_security_mfa_challenge",
            "access_security_login_audit",
            "access_security_alert",
            "access_security_alert_action",
            "access_security_ip_allowlist",
            "access_security_credential_access")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS")
          .isEqualTo("true");
    }
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("16.5.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seedBoth();

    // A 的会话 + B 的头 ⇒ 读到的仍是 A 的策略
    http.perform(get("/channel/access-security/policy")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.policyName").value("A 租户策略"));

    http.perform(get("/channel/access-security/ip-allowlist")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/access-security/policy").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("16.5.2.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    seedBoth();
    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "identity.audit.read", service::policy).policyName())
          .as("第 " + (i + 1) + " 轮：A 只看到 A 的策略")
          .isEqualTo("A 租户策略");
      assertThat(as(actorB, "identity.audit.read", service::policy).policyName())
          .as("第 " + (i + 1) + " 轮：B 只看到 B 的策略")
          .isEqualTo("B 租户策略");
      assertThat(as(actorA, "identity.audit.read", () -> service.allowlist(null, 0, 50)).total())
          .isEqualTo(1);
      assertThat(as(actorB, "identity.audit.read", () -> service.allowlist(null, 0, 50)).total())
          .isEqualTo(1);
    }

    // 池里那条连接归还后，租户上下文必须被清掉
    assertThat(countAsTenant(b, "select count(*) from access_security_ip_allowlist")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from access_security_ip_allowlist")).isEqualTo(1);
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
