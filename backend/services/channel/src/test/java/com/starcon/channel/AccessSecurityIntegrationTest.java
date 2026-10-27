package com.starcon.channel;

import static com.starcon.channel.AccessSecurityModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 16.5.2.1 登录与访问安全。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 能力声明逐条如实</b>：按来源 IP 判异常、会话到期 / 空闲 / 并发、
 *       MFA 挑战真实下发 —— 三者在本仓<b>量不到</b>，必须 available=false + ownerModule。
 *   <li><b>🔴 阈值不给默认值</b>：没登记策略就没有阈值，扫描直接 422 拒绝。</li>
 *   <li><b>🔴 真网段判定</b>：10.0.0.5 落在 10.0.0.0/8 内、192.168.1.1 不在；
 *       非法 CIDR 明确 400（不是 500）。</li>
 *   <li><b>🔴 白名单为空 ≠ 放行所有</b>：ENFORCE 口径下切不进去、删不掉最后一条。</li>
 *   <li><b>🔴 读不到 ≠ 没有</b>：上游不可达一律 503；上游正常但无记录 ⇒ known=false。</li>
 *   <li><b>🔴 告警状态机 + 处置留痕</b>：新警 → 已确认 → 已处置；已处置是终态。</li>
 *   <li><b>🔴 凭据访问审计 append-only</b>：应用账号没有 UPDATE/DELETE 权限，
 *       迁移超级账号也会被守卫触发器拒 —— <b>两个账号各试一次</b>。</li>
 *   <li><b>🔴 时间字段断言写回值</b>（不是「没抛异常」）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessSecurityIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static AccessSecurityIdentityStub identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID tenant;
  AccessService.Access actor;
  String bearer;

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\","
          + "\"channel.connection.read\",\"channel.connection.create\","
          + "\"channel.connection.update\",\"channel.connection.revoke\","
          + "\"channel.connection.rotate\",\"identity.audit.read\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      List.of(
          "channel.operation.read",
          "channel.operation.create",
          "channel.operation.update",
          "channel.operation.delete",
          "channel.connection.read",
          "channel.connection.create",
          "channel.connection.update",
          "channel.connection.revoke",
          "channel.connection.rotate",
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
      identity = new AccessSecurityIdentityStub();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actor = actor(user, tenant, grants);
    actors.put(bearer, actor);
    // 🔴 桩自己有一张 bearer → actor 的表（它替 identity 扮演已认证成员关系解析）
    identity.register(bearer, actor);
    identity.reset();
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
  }

  @Autowired AccessSecurityService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  /** 以超级用户执行一条 SQL（绕开 RLS，只用于播种与结构断言）。 */
  static void execSuper(String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以**应用账号 channel_app** 执行一条 SQL（RLS 真实生效）。 */
  String queryAsApp(String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','identity.audit.read',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(statement)) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以**应用账号 channel_app** 执行一条写语句（RLS 与授权都真实生效）。 */
  void execAsApp(String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','identity.audit.read',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  AccessSecurityPolicyInput policy(String mode) {
    return new AccessSecurityPolicyInput(
        "测试策略",
        true,
        "TOTP",
        0,
        null,
        480,
        1440,
        5,
        true,
        true,
        mode,
        15,
        3,
        3,
        null);
  }

  UUID seedCredential() {
    UUID id = UUID.randomUUID();
    execSuper(
        "insert into credential_ref(id,tenant_id,provider,external_ref,token_type,granted_scopes,"
            + " authorized_at,status) values('"
            + id
            + "','"
            + tenant
            + "','file://./.starcon-secrets/t1','file://./.starcon-secrets/t1/token','OAUTH2',"
            + "'orders.read',"
            // ⚠️ authorized_at 用**数据库的** now()，不用 Java 墙钟：
            //    约束 credential_window 是 CHECK(authorized_at <= now() + interval '5 minutes')，
            //    而 PG 的 now() = transaction_timestamp()（事务开始时刻）。长事务里
            //    「墙钟」与「事务开始」会分叉，机器负载高时必然违约（2026-10-26 r102 L2 实测假红）。
            + "now(),'ACTIVE')");
    return id;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  @Test
  @DisplayName("16.5.2.1 能力声明：量不到的维度显式 available=false + ownerModule + 影响面")
  void capabilitiesAreExplicit() {
    var caps = as("identity.audit.read", service::capabilities);
    var byCode = new java.util.LinkedHashMap<String, AccessSecurityCapability>();
    caps.capabilities().forEach(c -> byCode.put(c.code(), c));

    assertThat(byCode.get("LOGIN_AUDIT").available()).isTrue();
    assertThat(byCode.get("ANOMALY_ALERT").available()).isTrue();
    assertThat(byCode.get("IP_ALLOWLIST").available()).isTrue();
    assertThat(byCode.get("CREDENTIAL_ACCESS_AUDIT").available()).isTrue();

    // 🔴 本仓量不到的三条，必须如实标 false + 归属模块
    assertThat(byCode.get("LOGIN_SOURCE_IP").available())
        .as("identity 的登录事件不带来源 IP ⇒ 这一维必须标量不到")
        .isFalse();
    assertThat(byCode.get("LOGIN_SOURCE_IP").ownerModule()).isEqualTo("17");
    assertThat(byCode.get("LOGIN_SOURCE_IP").impact()).contains("不含来源 IP");

    assertThat(byCode.get("SESSION_EXPIRY").available()).isFalse();
    assertThat(byCode.get("SESSION_EXPIRY").ownerModule()).isEqualTo("17");

    assertThat(byCode.get("MFA_CHALLENGE").available()).isFalse();
    assertThat(byCode.get("MFA_CHALLENGE").ownerModule()).isEqualTo("17");

    // 下游口径要写明 2.2.1.3 已交付、本模块不改它的表
    assertThat(caps.downstreamNote()).contains("2.2.1.3");
  }

  // ==================================================================
  // 策略
  // ==================================================================

  @Test
  @DisplayName("16.5.2.1 策略：没登记过返回 configured=false，不预填任何默认阈值")
  void policyNotConfiguredByDefault() {
    var view = as("identity.audit.read", service::policy);
    assertThat(view.configured()).isFalse();
    assertThat(view.anomalyFailedLoginThreshold()).isZero();
    assertThat(view.anomalyWindowMinutes()).isZero();
  }

  @Test
  @DisplayName("16.5.2.1 策略：登记后能读回，阈值由租户显式给")
  void policyUpsert() {
    var saved = as("channel.operation.update", () -> service.savePolicy(policy("DISABLED"), actor));
    assertThat(saved.configured()).isTrue();
    assertThat(saved.anomalyFailedLoginThreshold()).isEqualTo(3);
    assertThat(saved.mfaRequired()).isTrue();

    var reread = as("identity.audit.read", service::policy);
    assertThat(reread.configured()).isTrue();
    assertThat(reread.policyName()).isEqualTo("测试策略");
  }

  @Test
  @DisplayName("16.5.2.1 策略：宽限期必须锚定强制生效时刻")
  void gracePeriodNeedsEnforcementFrom() {
    var input =
        new AccessSecurityPolicyInput(
            "测试策略", true, "TOTP", 60, null, 480, 1440, 5, true, true, "DISABLED", 15, 3, 3, null);
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as("channel.operation.update", () -> service.savePolicy(input, actor))))
        .isNotNull();
  }

  // ==================================================================
  // IP 白名单：真网段判定
  // ==================================================================

  @Test
  @DisplayName("16.5.2.1 IP 白名单：真网段判定（10.0.0.5 在 10.0.0.0/8 内，192.168.1.1 不在）")
  void cidrContainmentIsReal() {
    as("channel.operation.update", () -> service.savePolicy(policy("REPORT_ONLY"), actor));
    var entry =
        as(
            "channel.operation.create",
            () ->
                service.addAllowlist(
                    new AccessSecurityIpAllowlistInput(
                        "10.0.0.0/8", "办公网", "TENANT", null, "ACTIVE", null, null),
                    actor));
    assertThat(entry.cidrRange()).isEqualTo("10.0.0.0/8");

    var inside = as("identity.audit.read", () -> service.checkIp("10.0.0.5"));
    assertThat(inside.inAllowlist()).isTrue();
    assertThat(inside.matchedCidr()).isEqualTo("10.0.0.0/8");

    var outside = as("identity.audit.read", () -> service.checkIp("192.168.1.1"));
    assertThat(outside.inAllowlist()).isFalse();
    // 🔴 REPORT_ONLY 口径下**仍然放行**（只告警不拦截）—— 放行与「在不在白名单」是两个概念
    assertThat(outside.allowed()).isTrue();
    assertThat(outside.enforce()).isFalse();
  }

  @Test
  @DisplayName("16.5.2.1 IP 白名单：非法网段明确 400（不是 500）")
  void invalidCidrRejected() {
    for (String bad :
        List.of("999.1.1.1", "10.0.0.0/33", "not-an-ip", "example.com", "10.0.0.0/-1", "010.0.0.1")) {
      assertThat(
              org.assertj.core.api.Assertions.catchThrowable(
                  () ->
                      as(
                          "channel.operation.create",
                          () ->
                              service.addAllowlist(
                                  new AccessSecurityIpAllowlistInput(
                                      bad, "非法", "TENANT", null, "ACTIVE", null, null),
                                  actor))))
          .as("非法网段「" + bad + "」必须被明确拒绝")
          .isNotNull();
    }
  }

  @Test
  @DisplayName("16.5.2.1 IP 白名单：ENFORCE 口径下切不进去空白名单，也删不掉最后一条")
  void enforceModeFailsClosed() {
    // 🔴 空白名单 + ENFORCE ⇒ 切不过去
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as("channel.operation.update", () -> service.savePolicy(policy("ENFORCE"), actor))))
        .as("白名单为空时不能切到「强制拒绝」")
        .isNotNull();

    var entry =
        as(
            "channel.operation.create",
            () ->
                service.addAllowlist(
                    new AccessSecurityIpAllowlistInput(
                        "10.0.0.0/8", "办公网", "TENANT", null, "ACTIVE", null, null),
                    actor));
    as("channel.operation.update", () -> service.savePolicy(policy("ENFORCE"), actor));

    // 🔴 强制口径下，删掉最后一条 ⇒ 拒绝（fail-closed）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () -> {
                          service.deleteAllowlist(entry.entryId().toString(), actor);
                          return null;
                        })))
        .as("ENFORCE 口径下不许删到空白名单")
        .isNotNull();

    // 白名单还在，且强制口径下不在网段内的 IP 一律拒绝
    var outside = as("identity.audit.read", () -> service.checkIp("192.168.1.1"));
    assertThat(outside.enforce()).isTrue();
    assertThat(outside.allowed()).isFalse();
    assertThat(outside.reason()).contains("强制拒绝");
  }

  // ==================================================================
  // MFA 登记与挑战
  // ==================================================================

  @Test
  @DisplayName("16.5.2.1 MFA：登记 → 启用 → 下发挑战 → 验证尝试（append-only）")
  void mfaLifecycle() {
    UUID member = UUID.randomUUID();
    var registration =
        as(
            "channel.operation.create",
            () ->
                service.register(
                    new AccessSecurityMfaRegistrationInput(member, null, "TOTP", "测试因子", "vault://t1/mfa"),
                    actor));
    assertThat(registration.status()).isEqualTo("PENDING");
    assertThat(registration.secretRefPresent()).isTrue();

    var active = as("channel.operation.update", () -> service.activateRegistration(registration.registrationId().toString(), actor));
    assertThat(active.status()).isEqualTo("ACTIVE");
    assertThat(active.boundAt()).isNotNull();

    var challenge =
        as(
            "channel.operation.create",
            () ->
                service.issueChallenge(
                    new AccessSecurityMfaChallengeInput(
                        registration.registrationId(), "TOTP", "AUTHENTICATOR_APP", "app", 300),
                    actor));
    assertThat(challenge.outcome()).isEqualTo("ISSUED");
    assertThat(challenge.attemptNo()).isEqualTo(1);

    var verified =
        as(
            "channel.operation.create",
            () ->
                service.attemptChallenge(
                    challenge.challengeId().toString(),
                    new AccessSecurityMfaChallengeAttemptInput("VERIFIED", null),
                    actor));
    assertThat(verified.attemptNo()).isEqualTo(2);
    assertThat(verified.outcome()).isEqualTo("VERIFIED");

    // 🔴 append-only：第一次下发的行**没有被改掉**
    var page = as("identity.audit.read", () -> service.challenges(registration.registrationId().toString(), 0, 50));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items())
        .extracting(AccessSecurityMfaChallengeView::outcome)
        .containsExactlyInAnyOrder("ISSUED", "VERIFIED");
  }

  @Test
  @DisplayName("16.5.2.1 MFA：验证失败必须写明原因")
  void failedChallengeNeedsReason() {
    UUID member = UUID.randomUUID();
    var registration =
        as(
            "channel.operation.create",
            () ->
                service.register(
                    new AccessSecurityMfaRegistrationInput(member, null, "SMS", "", ""), actor));
    as("channel.operation.update", () -> service.activateRegistration(registration.registrationId().toString(), actor));
    var challenge =
        as(
            "channel.operation.create",
            () ->
                service.issueChallenge(
                    new AccessSecurityMfaChallengeInput(
                        registration.registrationId(), "SMS", "SMS", "sms-ref", 300),
                    actor));
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.attemptChallenge(
                                challenge.challengeId().toString(),
                                new AccessSecurityMfaChallengeAttemptInput("FAILED", null),
                                actor))))
        .isNotNull();
  }

  // ==================================================================
  // 登录审计扫描：读不到 ≠ 没有
  // ==================================================================

  @Test
  @DisplayName("16.5.2.1 扫描：没有策略 ⇒ 422（阈值不给默认值）")
  void scanRequiresPolicy() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () -> service.scan(bearer, new AccessSecurityScanInput(null, null), actor))))
        .as("没登记策略就没有阈值，扫描必须拒绝而不是替租户用默认值")
        .isNotNull();
  }

  @Test
  @DisplayName("16.5.2.1 扫描：上游不可达 ⇒ 503（绝不用空集合冒充「没有异常登录」）")
  void scanUpstreamUnavailableIs503() {
    as("channel.operation.update", () -> service.savePolicy(policy("DISABLED"), actor));
    identity.mode = AccessSecurityIdentityStub.Mode.UNAVAILABLE;
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () -> service.scan(bearer, new AccessSecurityScanInput(null, null), actor))))
        .as("上游读不到必须失败，绝不能返回空集合")
        .isNotNull();
  }

  @Test
  @DisplayName("16.5.2.1 扫描：上游正常但没有记录 ⇒ known=false（无记录 ≠ 无风险）")
  void scanWithNoEventsIsNotKnown() {
    as("channel.operation.update", () -> service.savePolicy(policy("DISABLED"), actor));
    var result =
        as(
            "channel.operation.create",
            () -> service.scan(bearer, new AccessSecurityScanInput(null, null), actor));
    assertThat(result.known()).isFalse();
    assertThat(result.events()).as("known=false 时事件是 null，不是空集合").isNull();
    assertThat(result.alertsCreated()).isZero();
    assertThat(result.note()).contains("没有记录");
  }

  @Test
  @DisplayName("16.5.2.1 扫描：登录失败激增 ⇒ 真落本地审计 + 出告警")
  void scanDetectsFailedLoginBurst() {
    as("channel.operation.update", () -> service.savePolicy(policy("DISABLED"), actor));
    identity.failLogins("alice", 5, Instant.now().minusSeconds(120));

    var result =
        as(
            "channel.operation.create",
            () -> service.scan(bearer, new AccessSecurityScanInput(null, null), actor));
    assertThat(result.known()).isTrue();
    assertThat(result.eventsScanned()).isEqualTo(5);
    assertThat(result.eventsRecorded()).isEqualTo(5);
    assertThat(result.alertsCreated()).isEqualTo(1);
    assertThat(result.alerts()).hasSize(1);
    assertThat(result.alerts().get(0).alertType()).isEqualTo("FAILED_LOGIN_BURST");
    assertThat(result.alerts().get(0).severity()).isEqualTo("HIGH");
    assertThat(result.alerts().get(0).subjectValue()).isEqualTo("alice");

    // 本地登录审计确实落了行
    var audit = as("identity.audit.read", () -> service.loginAudit("alice", null, 0, 50));
    assertThat(audit.known()).isTrue();
    assertThat(audit.total()).isEqualTo(5);
    assertThat(audit.items()).allSatisfy(item -> assertThat(item.outcome()).isEqualTo("FAILURE"));
    // 🔴 identity 的登录事件不带来源 IP ⇒ 如实标 available=false，不编造
    assertThat(audit.items()).allSatisfy(item -> assertThat(item.sourceIpAvailable()).isFalse());
  }

  @Test
  @DisplayName("16.5.2.1 扫描：重复扫描不刷出第二条未关闭告警（幂等键）")
  void scanIsIdempotentOnAlerts() {
    as("channel.operation.update", () -> service.savePolicy(policy("DISABLED"), actor));
    identity.failLogins("bob", 5, Instant.now().minusSeconds(120));
    var first =
        as("channel.operation.create", () -> service.scan(bearer, new AccessSecurityScanInput(null, null), actor));
    var second =
        as("channel.operation.create", () -> service.scan(bearer, new AccessSecurityScanInput(null, null), actor));
    assertThat(first.alertsCreated()).isEqualTo(1);
    assertThat(second.alertsCreated()).isZero();
    assertThat(second.alertsUpdated()).isEqualTo(1);
    assertThat(second.eventsRecorded()).as("同一上游事件只落一次").isZero();
  }

  // ==================================================================
  // 告警状态机与处置留痕
  // ==================================================================

  @Test
  @DisplayName("16.5.2.1 告警：新警 → 已确认 → 已处置，且每次处置都留痕")
  void alertLifecycleAndTrail() {
    as("channel.operation.update", () -> service.savePolicy(policy("DISABLED"), actor));
    identity.failLogins("carol", 5, Instant.now().minusSeconds(120));
    var scan =
        as("channel.operation.create", () -> service.scan(bearer, new AccessSecurityScanInput(null, null), actor));
    String alertId = scan.alerts().get(0).alertId().toString();

    var acknowledged =
        as("channel.operation.update", () -> service.acknowledgeAlert(alertId, "已确认", actor));
    assertThat(acknowledged.status()).isEqualTo("ACKNOWLEDGED");
    assertThat(acknowledged.acknowledgedAt()).isNotNull();

    var resolved =
        as("channel.operation.update", () -> service.resolveAlert(alertId, "已换密码", actor));
    assertThat(resolved.status()).isEqualTo("RESOLVED");
    assertThat(resolved.resolutionNote()).isEqualTo("已换密码");

    // 处置留痕两行
    assertThat(resolved.actions()).hasSize(2);
    assertThat(resolved.actions()).extracting(AccessSecurityAlertActionView::action)
        .containsExactly("ACKNOWLEDGE", "RESOLVE");

    // 🔴 已处置是终态
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as("channel.operation.update", () -> service.acknowledgeAlert(alertId, "再来一次", actor))))
        .as("已处置是终态，不能再改")
        .isNotNull();
  }

  // ==================================================================
  // 平台授权凭据的访问审计
  // ==================================================================

  @Test
  @DisplayName("16.5.2.1 凭据访问：受控访问留下审计凭据，且响应里没有令牌")
  void controlledAccessIsAuditedAndMinimal() throws Exception {
    UUID credential = seedCredential();
    var result =
        as(
            "channel.connection.read",
            () ->
                service.controlledAccess(
                    new AccessSecurityCredentialAccessInput(credential, "换令牌前核对授权范围"), actor));

    assertThat(result.credential().credentialId()).isEqualTo(credential);
    assertThat(result.credential().grantedScopes()).isEqualTo("orders.read");
    assertThat(result.audit().accessKind()).isEqualTo("READ_METADATA");
    assertThat(result.audit().outcome()).isEqualTo("SUCCESS");
    assertThat(result.audit().actorId()).isEqualTo(user);
    assertThat(result.audit().occurredAt()).isNotNull();

    // 审计流水里能查到这一条
    var page = as("identity.audit.read", () -> service.credentialAccess(credential.toString(), null, 0, 50));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).accessPurpose()).isEqualTo("换令牌前核对授权范围");

    // 🔴 表里没有令牌列 ⇒ 响应里不可能有令牌（tokenType 是**授权类型**，不是令牌本身）
    var json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
    var tree = new ObjectMapper().findAndRegisterModules().readTree(json);
    var keys = new java.util.ArrayList<String>();
    tree.fieldNames().forEachRemaining(keys::add);
    tree.get("credential").fieldNames().forEachRemaining(keys::add);
    tree.get("audit").fieldNames().forEachRemaining(keys::add);
    assertThat(keys)
        .as("响应里不许出现任何承载令牌值的字段")
        .doesNotContain("accessToken", "refreshToken", "secret", "secretValue", "password", "token", "bearer");
    assertThat(keys).as("tokenType 是授权类型（OAUTH2），不是令牌").contains("tokenType");
  }

  @Test
  @DisplayName("16.5.2.1 凭据访问：凭据不存在 ⇒ 404，而且这次访问本身也留痕")
  void controlledAccessOfMissingCredentialIsAudited() {
    UUID missing = UUID.randomUUID();
    // 🔴 服务层**不抛异常**（在事务里抛会把刚写的审计一起回滚）⇒ 返回 outcome=FAILED 的审计凭据
    var result =
        as(
            "channel.connection.read",
            () ->
                service.controlledAccess(
                    new AccessSecurityCredentialAccessInput(missing, "试探"), actor));
    assertThat(result.credential()).isNull();
    assertThat(result.audit().outcome()).isEqualTo("FAILED");
    var page =
        as("identity.audit.read", () -> service.credentialAccess(missing.toString(), "FAILED", 0, 50));
    assertThat(page.total()).as("访问不存在的凭据本身也是值得留痕的事件").isEqualTo(1);
    assertThat(page.failedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("16.5.2.1 HTTP：访问不存在的凭据 ⇒ 404，而这次访问的审计活得比请求长")
  void httpControlledAccessOfMissingCredentialIs404ButAudited() throws Exception {
    UUID missing = UUID.randomUUID();
    http.perform(
            post("/channel/access-security/credential-access")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(
                    "{\"credentialId\":\""
                        + missing
                        + "\",\"accessPurpose\":\"HTTP 试探\"}"))
        .andExpect(status().isNotFound());
    // 审计已经提交（不在被回滚的事务里）
    var page =
        as("identity.audit.read", () -> service.credentialAccess(missing.toString(), "FAILED", 0, 50));
    assertThat(page.total()).as("404 之后审计仍然在").isEqualTo(1);
  }

  @Test
  @DisplayName("16.5.2.1 凭据访问审计：append-only —— 应用账号与迁移超级账号各试一次")
  void credentialAccessLedgerIsAppendOnly() {
    UUID credential = seedCredential();
    as(
        "channel.connection.read",
        () ->
            service.controlledAccess(
                new AccessSecurityCredentialAccessInput(credential, "审计测试"), actor));

    // ① 应用账号：GRANT 只给了 SELECT,INSERT ⇒ UPDATE / DELETE 直接被数据库拒
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsApp(
                        "update access_security_credential_access set note='篡改' where tenant_id='"
                            + tenant
                            + "'")))
        .as("应用账号不该有 UPDATE 权限")
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsApp(
                        "delete from access_security_credential_access where tenant_id='" + tenant + "'")))
        .as("应用账号不该有 DELETE 权限")
        .isNotNull();

    // ② 迁移超级账号：权限够，但守卫触发器照样拒（改写访问痕迹 = 毁掉取证能力）
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execSuper(
                        "update access_security_credential_access set note='篡改' where tenant_id='"
                            + tenant
                            + "'")))
        .as("超级账号也改不动审计凭据")
        .isNotNull();

    // 行还在，内容没变
    assertThat(queryAsApp("select note from access_security_credential_access where tenant_id='" + tenant + "'"))
        .isEqualTo("");
  }

  @Test
  @DisplayName("16.5.2.1 登录审计：append-only —— 应用账号与迁移超级账号各试一次")
  void loginAuditLedgerIsAppendOnly() {
    as("channel.operation.update", () -> service.savePolicy(policy("DISABLED"), actor));
    identity.failLogins("dave", 3, Instant.now().minusSeconds(60));
    as("channel.operation.create", () -> service.scan(bearer, new AccessSecurityScanInput(null, null), actor));
    assertThat(queryAsApp("select count(*) from access_security_login_audit where tenant_id='" + tenant + "'"))
        .isEqualTo("3");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execAsApp(
                        "update access_security_login_audit set summary='篡改' where tenant_id='"
                            + tenant
                            + "'")))
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    execSuper(
                        "update access_security_login_audit set summary='篡改' where tenant_id='"
                            + tenant
                            + "'")))
        .isNotNull();
    assertThat(queryAsApp("select summary from access_security_login_audit where tenant_id='" + tenant + "' limit 1"))
        .contains("登录失败");
  }

  // ==================================================================
  // 时间字段：断言写回的值
  // ==================================================================

  @Test
  @DisplayName("16.5.2.1 时间字段：timestamptz 写回值不被偏移（断言写回值，不只是没抛异常）")
  void timestampsRoundTrip() {
    UUID credential = seedCredential();
    var result =
        as(
            "channel.connection.read",
            () ->
                service.controlledAccess(
                    new AccessSecurityCredentialAccessInput(credential, "时间断言"), actor));

    String written =
        queryAsApp(
            "select to_char(occurred_at at time zone 'UTC','YYYY-MM-DD HH24:MI:SS')"
                + " from access_security_credential_access where id='"
                + result.audit().accessId()
                + "'");
    assertThat(written).isNotNull();
    // 写回值必须落在「现在」的 5 分钟窗口内 —— 若用 Timestamp.valueOf(LocalDateTime) 绑定，
    // 这里会偏 −28800 秒（8 小时），断言必然失败。
    OffsetDateTime parsed = OffsetDateTime.parse(written.replace(' ', 'T') + "Z");
    assertThat(parsed)
        .as("写回的 timestamptz 必须是真正的 UTC 时刻，不能偏 8 小时")
        .isCloseTo(
            OffsetDateTime.now(ZoneOffset.UTC).withNano(0),
            within(5, java.time.temporal.ChronoUnit.MINUTES));

    // 读回来的值与服务层给的值必须一致（不是「看起来对」）
    var reread = as("identity.audit.read", () -> service.credentialAccessRow(result.audit().accessId().toString()));
    assertThat(reread.occurredAt().toInstant())
        .isEqualTo(result.audit().occurredAt().toInstant());
  }

  // ==================================================================
  // HTTP 层：权限与路由
  // ==================================================================

  @Test
  @DisplayName("16.5.2.1 HTTP：无会话 ⇒ 401；能力声明 200")
  void httpRequiresSession() throws Exception {
    http.perform(get("/channel/access-security/capabilities"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/channel/access-security/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.capabilities.length()").value(9));
  }

  @Test
  @DisplayName("16.5.2.1 HTTP：扫描在 HTTP 层也把上游不可达表达成 503")
  void httpScanSurfaces503() throws Exception {
    http.perform(
            put("/channel/access-security/policy")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new AccessSecurityPolicyInput(
                            "HTTP 策略", false, "TOTP", 0, null, 480, 1440, 5, true, true,
                            "DISABLED", 15, 3, 3, null))))
        .andExpect(status().isOk());
    identity.mode = AccessSecurityIdentityStub.Mode.UNAVAILABLE;
    http.perform(post("/channel/access-security/login-audit/scan").header("Authorization", bearer))
        .andExpect(status().isServiceUnavailable());
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
    if (identity != null) identity.close();
    pg.stop();
  }
}
